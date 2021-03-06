package org.javacs.message;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class JavacArgs {

    /**
     * Source of the file we want to compile
     */
    public String text;

    /**
     * Path to a file we want to compile. But we'll use content for the actual source code.
     */
    public String path;
}
