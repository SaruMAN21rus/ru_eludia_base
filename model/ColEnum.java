package ru.eludia.base.model;

public interface ColEnum {
    
    Col getCol ();
    
    default String lc () {
        return this.toString ().toLowerCase ();
    }
    
}