package ru.eludia.base.db.sql.gen;

import java.util.ArrayList;
import ru.eludia.base.model.Table;

public abstract class Join extends Part<Join> {
    
    Select select;
    JoinCondition joinCondition;
    boolean inner = true;

    public Join (Select select, Table table, String... names) {
        super (table, names);
        this.select = select;
    }

    public final JoinCondition getJoinCondition () {
        return joinCondition;
    }
    
    public final Join or0 () {
        inner = false;
        return this;
    }

    public final boolean isInner () {
        if (inner) return true;        
        for (Filter f: filters) if (!f.forJoinOnly) return true;
        return false;
    }
    
    public final Select on () {
        return on ("");
    }
    
    public abstract Select on (String hint);
    
    public final Join when (String src, Object... values) {        
        if (filters.isEmpty ()) filters = new ArrayList<> ();       
        final Filter filter = new Filter (this, src, values);
        if (!filter.isOff ()) {
            filter.setForJoinOnly (true);
            filters.add (filter);
        }
        return this;
    }
        
}