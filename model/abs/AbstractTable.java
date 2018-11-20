package ru.eludia.base.model.abs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import ru.eludia.base.model.ColEnum;
import ru.eludia.base.model.Trigger;

public abstract class AbstractTable<C extends AbstractCol, K extends AbstractKey> extends NamedObject {
    
    protected Roster<C> columns        = new Roster<> ();
    protected Roster<K> keys           = new Roster<> ();
    protected Roster<Trigger> triggers = new Roster<> ();
    List<C> pk = null;

    public final Roster<C> getColumns () {
        return columns;
    }

    public Roster<K> getKeys () {
        return keys;
    }

    public Roster<Trigger> getTriggers () {
        return triggers;
    }

    public AbstractTable (String name) {
        super (name);
    }
    
    public AbstractTable (String name, String remark) {
        super (name, remark);
    }
    
    public C getColumn (ColEnum c) {
        return columns.get (c.lc ());
    }
    
    public C getColumn (String name) {
        return columns.get (name);
    }
    
    public List<C> getPk () {
        return pk;
    }

    public Collection<String> getKeyColNames () {
        return keyColNames;
    }
    
    Collection<String> keyColNames = Collections.EMPTY_LIST;
    
    public final void pk (ColEnum c, String... aliases) {
        final C column = getColumn (c);
        if (column == null) throw new IllegalArgumentException ("Column " + c + " not found in " + this.getName () + ". Existing column names are " + getColumns ().keySet ());
        pk (column, aliases);
    }
    
    public final void pk (C c, String... aliases) {
        
        if (!columns.containsKey (c.getName ())) add (c, aliases);
        
        if (pk == null) pk = new ArrayList<> (1);
        
        pk.add (c);
        
        final String name = c.getName ();
        
        switch (pk.size ()) {
            case 1:
                keyColNames = Collections.singletonList (name);
                break;
            case 2:
                keyColNames = new ArrayList<> (keyColNames);
            default:
                keyColNames.add (name);
        }
        
    }
    
    public final void add (C c, String... aliases) {
        columns.add (c, aliases);
    }

    public final void add (K k) {
        keys.add (k);
    }
    
}