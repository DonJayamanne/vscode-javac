package org.javacs;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.api.MultiTaskListener;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Check;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.comp.Todo;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.parser.FuzzyParserFactory;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Options;

import javax.tools.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Maintains a reference to a Java compiler, 
 * and several of its internal data structures,
 * which we need to fiddle with to get incremental compilation 
 * and extract the diagnostic information we want.
 */
public class JavacHolder {
    private static final Logger LOG = Logger.getLogger("main");
    private final List<Path> classPath;
    private final List<Path> sourcePath;
    private final Path outputDirectory;
    // javac places all of its internal state into this Context object,
    // which is basically a Map<String, Object>
    public final Context context = new Context();
    // Error reporting initially goes nowhere
    // When we want to report errors back to VS Code, we'll replace this with something else
    private DiagnosticListener<JavaFileObject> errorsDelegate = diagnostic -> {};
    // javac isn't friendly to swapping out the error-reporting DiagnosticListener,
    // so we install this intermediate DiagnosticListener, which forwards to errorsDelegate
    private final DiagnosticListener<JavaFileObject> errors = diagnostic -> {
        errorsDelegate.report(diagnostic);
    };

    {
        context.put(DiagnosticListener.class, errors);
    }
    // IncrementalLog registers itself in context and pre-empts the normal Log from being created
    private final IncrementalLog log = new IncrementalLog(context);
    public final JavacFileManager fileManager = new JavacFileManager(context, true, null);
    private final Check check = Check.instance(context);
    // FuzzyParserFactory registers itself in context and pre-empts the normal ParserFactory from being created
    private final FuzzyParserFactory parserFactory = FuzzyParserFactory.instance(context);
    private final Options options = Options.instance(context);
    private final JavaCompiler compiler = JavaCompiler.instance(context);

    {
        compiler.keepComments = true;
    }

    private final Todo todo = Todo.instance(context);
    private final JavacTrees trees = JavacTrees.instance(context);
    // TreeScanner tasks we want to perform before or after compilation stages
    // We'll use these scanners to implement features like go-to-definition
    private final Map<TaskEvent.Kind, List<TreeScanner>> beforeTask = new HashMap<>(), afterTask = new HashMap<>();
    private final ClassIndex index = new ClassIndex(context);

    public JavacHolder(List<Path> classPath, List<Path> sourcePath, Path outputDirectory) {
        this.classPath = classPath;
        this.sourcePath = sourcePath;
        this.outputDirectory = outputDirectory;

        options.put("-classpath", Joiner.on(":").join(classPath));
        options.put("-sourcepath", Joiner.on(":").join(sourcePath));
        options.put("-d", outputDirectory.toString());

        MultiTaskListener.instance(context).add(new TaskListener() {
            @Override
            public void started(TaskEvent e) {
                LOG.info("started " + e);

                JCTree.JCCompilationUnit unit = (JCTree.JCCompilationUnit) e.getCompilationUnit();

                List<TreeScanner> todo = beforeTask.getOrDefault(e.getKind(), Collections.emptyList());

                for (TreeScanner visitor : todo) {
                    unit.accept(visitor);
                }
            }

            @Override
            public void finished(TaskEvent e) {
                LOG.info("finished " + e);

                JCTree.JCCompilationUnit unit = (JCTree.JCCompilationUnit) e.getCompilationUnit();

                if (e.getKind() == TaskEvent.Kind.ANALYZE)
                    unit.accept(index);

                List<TreeScanner> todo = afterTask.getOrDefault(e.getKind(), Collections.emptyList());

                for (TreeScanner visitor : todo) {
                    unit.accept(visitor);
                }
            }
        });

        clearOutputDirectory(outputDirectory);
    }

    private static void clearOutputDirectory(Path file) {
        try {
            if (file.getFileName().toString().endsWith(".class")) {
                LOG.info("Invalidate " + file);

                Files.setLastModifiedTime(file, FileTime.from(Instant.EPOCH));
            }
            else if (Files.isDirectory(file))
                Files.list(file).forEach(JavacHolder::clearOutputDirectory);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    /**
     * After the parse phase of compilation,
     * scan the source trees with these scanners.
     * Replaces any existing after-parse scanners.
     */
    public void afterParse(TreeScanner... scan) {
        afterTask.put(TaskEvent.Kind.PARSE, ImmutableList.copyOf(scan));
    }

    /**
     * After the analysis phase of compilation,
     * scan the source trees with these scanners.
     * Replaces any existing after-analyze scanners.
     */
    public void afterAnalyze(TreeScanner... scan) {
        afterTask.put(TaskEvent.Kind.ANALYZE, ImmutableList.copyOf(scan));
    }

    /**
     * Send all errors to callback, replacing any existing callback
     */
    public void onError(DiagnosticListener<JavaFileObject> callback) {
        errorsDelegate = callback;
    }

    /**
     * Compile the indicated source file, and its dependencies if they have been modified.
     * Clears source from internal caches of javac, so that compile(parse(source)) will re-compile.
     */
    public JCTree.JCCompilationUnit parse(JavaFileObject source) {
        StringJoiner command = new StringJoiner(" ");
        
        command.add("javac");
        
        for (String key : options.keySet()) {
            String value = options.get(key);
            
            command.add(key);
            command.add(value);
        }
        
        if (source instanceof SimpleJavaFileObject) {
            SimpleJavaFileObject simple = (SimpleJavaFileObject) source;
            
            command.add(simple.toUri().getPath());
        }
        
        LOG.info(command.toString());
        
        clear(source);

        JCTree.JCCompilationUnit result = compiler.parse(source);

        return result;
    }

    /**
     * Compile a source tree produced by this.parse
     */
    public void compile(JCTree.JCCompilationUnit source) {
        compiler.processAnnotations(compiler.enterTrees(com.sun.tools.javac.util.List.of(source)));

        while (!todo.isEmpty()) {
            // We don't do the desugar or generate phases, because they remove method bodies and methods
            Env<AttrContext> next = todo.remove();
            Env<AttrContext> attributedTree = compiler.attribute(next);
            Queue<Env<AttrContext>> analyzedTree = compiler.flow(attributedTree);
        }
    }

    /**
     * Remove source file from caches in the parse stage
     */
    private void clear(JavaFileObject source) {
        // Forget about this file
        log.clear(source);

        // javac's flow stage will stop early if there are errors
        log.nerrors = 0;
        log.nwarnings = 0;

        // Remove all cached classes that came from this files
        List<Name> remove = new ArrayList<>();

        check.compiled.forEach((name, symbol) -> {
            if (symbol.sourcefile.getName().equals(source.getName()))
                remove.add(name);
        });

        remove.forEach(check.compiled::remove);
    }
}
