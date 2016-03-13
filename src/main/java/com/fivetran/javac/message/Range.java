package com.fivetran.javac.message;

public class Range {
    /**
     * The start position. It is before or equal to [end](#Range.end).
     */
    public final Position start;

    /**
     * The end position. It is after or equal to [start](#Range.start).
     */
    public final Position end;

    public Range(Position start, Position end) {
        this.start = start;
        this.end = end;
    }
}