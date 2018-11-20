package ru.eludia.base.db.sql.gen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import ru.eludia.base.model.Col;
import ru.eludia.base.model.ColEnum;
import ru.eludia.base.model.Table;

public abstract class Part<T extends Part> {
    
    protected final Logger logger = Logger.getLogger (this.getClass ().getName ());
    
    Table table;
    String alias;
    ResultCol [] columns;
    List<Filter> filters = Collections.EMPTY_LIST;
        
    private static ResultCol [] NO_COLS = new ResultCol [0];

    Part (Table table, String... names) {
        
        this.table = table;
                
        if (names.length == 0) {
            columns = NO_COLS;
            return;
        }
        
        int from = 0;
        
        if (names [0].startsWith ("AS ")) {
            alias = names [0].substring (3);
            if (names.length == 1) {
                columns = NO_COLS;
                return;
            }
            from = 1;
        }
        
        if ("*".equals (names [from])) {
            
            Collection<Col> cols = table.getColumns ().values ();
            
            columns = new ResultCol [cols.size () + names.length - from - 1];
            
            int i = 0;
            
            for (Col c: cols)

                columns [i ++] = new ResultCol (table, c.getName ());
            
            for (int j = from + 1; j < names.length; j ++)

                columns [i ++] = new ResultCol (table, names [j]);
            
        }
        else {
            
            columns = new ResultCol [names.length - from];

            for (int i = from; i < names.length; i ++)
                
                columns [i - from] = new ResultCol (table, names [i]);
        
        }
        
    }

    public final T and (String name, Predicate predicate) {
        andEither (name, predicate);
        return (T) this;        
    }

    public final T and (String src, Object... values) {
        andEither (src, values);
        return (T) this;
    }
    
    public final T and (ColEnum col, Operator op, Object... values) {
        andEither (col, op, values);
        return (T) this;
    }

    public Filter andEither (ColEnum col, Operator op, Object... values) {
        if (filters.isEmpty ()) filters = new ArrayList<> ();
        final Filter filter = new Filter (this, col.getCol ().getName ().toLowerCase (), new Predicate (op, values));
        if (!filter.isOff ()) filters.add (filter);
        return filter;
    }
    
    public Filter andEither (String name, Predicate predicate) {
        if (filters.isEmpty ()) filters = new ArrayList<> ();       
        final Filter filter = new Filter (this, name, predicate);
        if (!filter.isOff ()) filters.add (filter);
        return filter;        
    }

    public final Filter andEither (String src, Object... values) {
        if (filters.isEmpty ()) filters = new ArrayList<> ();       
        final Filter filter = new Filter (this, src, values);
        if (!filter.isOff ()) filters.add (filter);
        return filter;
    }
    
    public final T where (String src, Object... values) {
        return and (src, values);
    }

    public Table getTable () {
        return table;
    }

    public ResultCol [] getColumns () {
        return columns;
    }
        
    public final String getTableAlias () {
        return alias != null ? alias : table.getName ();
    }
    
    public final boolean hasFilters () {
        return !filters.isEmpty ();
    }

    public List<Filter> getFilters () {
        return filters;
    }    

}