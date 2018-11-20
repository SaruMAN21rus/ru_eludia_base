package ru.eludia.base.model;

import javax.json.Json;
import javax.json.JsonObjectBuilder;
import ru.eludia.base.model.abs.AbstractCol;
import ru.eludia.base.model.def.Def;
import ru.eludia.base.model.phys.PhysicalCol;

public class Col extends AbstractCol implements Cloneable {
    
    Type type;    
    Def def = null;
    Table table;
    PhysicalCol physicalCol;
    
    public void setTable (Table table) {
        this.table = table;
    }

    public Def getDef () {
        return def;
    }

    public void setDef (Def def) {
        this.def = def;
    }

    public Type getType () {
        return type;
    }

    public Col clone (String name) {
        Col c = clone ();
        c.name = name;
        return c;
    }
    
    @Override
    public Col clone () {

        Col clone;
        
        try {
            clone = (Col) super.clone ();
        }
        catch (CloneNotSupportedException ex) {
            throw new IllegalStateException ("Impossible", ex);
        }

        clone.name = name;
        clone.remark = remark;

        clone.type = type;
        clone.def = def;

        clone.length = length;
        clone.precision = precision;

        return clone;

    }
        
    public Col (Object name, Type type, Object... p) {
        
        super (name.toString ().toLowerCase (), p [p.length - 1].toString ());
        
        this.type = type;
        
        int len = p.length;
        
        if (len == 1) return;
        
        Object tail = p [len - 2];
        
        if (tail == null)
            nullable = true;        
        else if (tail instanceof Def) 
            def = (Def) tail;

        if (p [0] instanceof Integer) {
            length = (Integer) p [0];
            if (len == 2) return;
            if (p [1] instanceof Integer) precision = (Integer) p [1];
        }
                
    }
    
    public Table getTable () {
        return table;
    }
    
    @Override
    public String toString () {
        
        StringBuilder sb = new StringBuilder (type.name ());
        
        if (length > 0) {
            sb.append ('[');
            sb.append (length);
            if (precision > 0) {
                sb.append (',');
                sb.append (precision);
            }
            
            sb.append (']');            
        }
        
        final JsonObjectBuilder job = Json.createObjectBuilder ()
            .add ("name", name)
            .add ("type", sb.toString ());
        
        if (def == null) job.addNull ("def"); else job.add ("def", def.toString ());

        return job           
            .add ("nullable", nullable)
            .add ("remark", remark)
        .build ().toString ();
        
    }

    public PhysicalCol toPhysical () {
        return physicalCol;
    }

    public void setPhysicalCol (PhysicalCol physicalCol) {
        this.physicalCol = physicalCol;
    }

}