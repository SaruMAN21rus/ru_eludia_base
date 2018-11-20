package ru.eludia.base.db.dialect;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.eludia.base.DB;
import ru.eludia.base.db.sql.build.QP;
import ru.eludia.base.db.sql.build.TableSQLBuilder;
import ru.eludia.base.model.Col;
import ru.eludia.base.model.Key;
import ru.eludia.base.Model;
import ru.eludia.base.model.Ref;
import ru.eludia.base.model.Table;
import ru.eludia.base.model.Trigger;
import ru.eludia.base.model.View;
import ru.eludia.base.model.abs.AbstractCol;
import ru.eludia.base.model.def.Def;
import ru.eludia.base.model.def.Virt;
import ru.eludia.base.model.diff.Diff;
import ru.eludia.base.model.diff.NullAction;
import ru.eludia.base.model.diff.TypeAction;
import ru.eludia.base.model.phys.PhysicalCol;
import ru.eludia.base.model.phys.PhysicalKey;
import ru.eludia.base.model.phys.PhysicalModel;
import ru.eludia.base.model.phys.PhysicalTable;
import ru.eludia.base.db.sql.gen.Filter;
import ru.eludia.base.db.sql.gen.Join;
import ru.eludia.base.db.sql.gen.Part;
import ru.eludia.base.db.sql.gen.Predicate;
import ru.eludia.base.db.sql.gen.ResultCol;
import ru.eludia.base.db.sql.gen.Select;

public abstract class ANSI extends DB {
    
    public ANSI (Connection cn, Model model) {
        super (cn, model);
    }

    void setNumericParam (PreparedStatement st, int n, Object value) throws SQLException {
        
        if (value instanceof Integer) {
            st.setInt (n, (Integer) value);
        }
        else if (value instanceof Boolean) {
            st.setInt (n, (Boolean) value ? 1 : 0);
        }
        else if (value instanceof BigDecimal) {
            st.setBigDecimal (n, (BigDecimal) value);
        }
        else if (value instanceof Long) {
            st.setLong (n, (Long) value);
        }
        else if (value instanceof Short) {
            st.setShort (n, (Short) value);
        }
        else {
            st.setBigDecimal (n, new BigDecimal (value.toString ()));
        }

    }
    
    protected void setNotNullParam (PreparedStatement st, int n, JDBCType type, Object value) throws SQLException {
        
        if (value instanceof Def) value = ((Def) value).getValue ();
        
        switch (type) {
            
            case NUMERIC:
                setNumericParam (st, n, value);
                break;

            case DATE:
            case TIMESTAMP:
                st.setTimestamp (n, to.timestamp (value));
                break;

            case VARBINARY: 
                String vb = toVarbinary (value);
                st.setString (n, vb);
                break;
                    
            case BLOB: 
                st.setBinaryStream (n, to.binaryStream (value));
                break;

            default: 
                st.setString (n, value.toString ());
        
        }
        
    }
    
    protected void setNullParam (PreparedStatement st, int n, JDBCType type) throws SQLException {
        
        logger.log (Level.FINE, "setNullParam: n={0}, type = {1}, value = {2}", new Object[]{n, type});
        
        switch (type) {
            
            case VARCHAR:
                st.setNull (n, Types.VARCHAR);
                break;
                
            case NUMERIC:
                st.setNull (n, Types.NUMERIC);
                break;

            case DATE:
                st.setNull (n, Types.DATE);
                break;

            case TIMESTAMP:
                st.setNull (n, Types.TIMESTAMP);
                break;

            case VARBINARY: 
                st.setNull (n, Types.VARBINARY);
                break;
                    
            case BLOB: 
                st.setNull (n, Types.BLOB);
                break;

            default: 
                logger.warning ("Setting OTHER NULL for :" + n);
                st.setNull (n, Types.OTHER);
        
        }
        
    }
    
    @Override
    public final void setParam (PreparedStatement st, int n, JDBCType type, Object value) throws SQLException {
        
        if (value == null) setNullParam (st, n, type); else setNotNullParam (st, n, type, value);
        
    }

    protected void genInsertSql (TableSQLBuilder b) {
        b.append ("INSERT INTO ");
        b.append (b.getTable ().getName ());
        b.append (" (");
        for (PhysicalCol i: b.getCols ()) {
            b.append (i.getName ());
            b.append (',');
        }
        b.setLastChar (')');
        b.append (" VALUES (");
        for (PhysicalCol i: b.getCols ()) b.append ("?,");
        b.setLastChar (')');
    }
    
    protected void genUpdateSql (TableSQLBuilder b) {
                
        b.append ("UPDATE ");
        b.append (b.getTable ().getName ());
        b.append (" SET ");
        for (PhysicalCol i: b.getNonKeyCols ()) {
            b.append (i.getName ());
            b.append ("=?,");
        }
        b.setLastChar (' ');
        b.append ("WHERE ");
        for (PhysicalCol i: b.getKeyCols ()) {
            if (b.getLastChar () == '?') b.append (" AND ");
            b.append (i.getName ());
            b.append ("=?");
        }

    }
    
    QP createSelectQP () {
        return new QP ("SELECT ");
    }
    
    void addSelectOwnColumns (Select select, QP qp) {
        for (ResultCol col: select.getColumns ()) 
            addSelectOwnColumn (qp, select, col);
    }

    private void addSelectJoinedColumns (Select select, QP qp) {
        for (Join j: select.getJoins ())
            for (ResultCol col: j.getColumns ())
                addSelectJoinedColumn (qp, j, col);
    }
    
    void quoteOpen (QP qp) {
        qp.append ('"');
    }
    
    void quoteClose (QP qp) {
        qp.append ('"');
    }
    
    /**
     * Добавление к формируемому SQL списка закавыченных имён через запятую.
     * @param terms имена
     */
    public final void addQuotedList (QP qp, String... terms) {
        
        int last = terms.length - 1;
        
        if (last < 0) return;
        
        for (int i = 0; i < last; i ++) {
            quoteOpen (qp);
            qp.append (terms [i]);
            quoteClose (qp);
            qp.append (',');
        }        
        
        quoteOpen (qp);
        qp.append (terms [last]);
        quoteClose (qp);
        
    }
    
    
    void addSelectColumn (QP qp, boolean srcTable, boolean aliasTable, Part part, ResultCol col) {
        
        if (srcTable) {
            qp.append (part.getTableAlias ());
            qp.append ('.');
        }
        
        qp.append (col.getName ());
        
        qp.append (' ');
        quoteOpen (qp);
        
        if (col.getAlias () != null) {
            qp.append (col.getAlias ());
        }
        else {
            if (aliasTable) {
                qp.append (part.getTableAlias ().toLowerCase ());
                qp.append ('.');                
            }
            qp.append (col.getName ().toLowerCase ());
        }

        quoteClose (qp);
        qp.append (',');        

    }
    
    void addSelectOwnColumn (QP qp, Select select, ResultCol col) {
        addSelectColumn (qp, !select.getJoins ().isEmpty (), false, select, col);
    }

    void addSelectJoinedColumn (QP qp, Join join, ResultCol col) {
        addSelectColumn (qp, true, true, join, col);
    }

    void addSelectColumns (Select select, QP qp) {
        addSelectOwnColumns (select, qp);
        addSelectJoinedColumns (select, qp);
        qp.setLastChar (' ');        
    }
    
    void addSelectFilter (QP qp, Part part, Filter f) {
        
        Filter nextFilter = f.getNextFilter ();
        
        if (nextFilter != null) qp.append ('(');
        
        Predicate p = f.getPredicate ();

        if (p.isNot ()) qp.append ("NOT(");
        
        if (p.isOrNull ()) {
            qp.append ('(');
            qp.append (part.getTableAlias ());
            qp.append ('.');
            qp.append (f.getColumn ().getName ());
            qp.append (" IS NULL OR ");
        }

        qp.append (part.getTableAlias ());
        qp.append ('.');
        qp.append (f.getColumn ().getName ());

        p.appendTo (qp, f.getColumn ().toPhysical (), this);

        if (p.isOrNull ()) qp.append (')');
        
        if (p.isNot ()) qp.append (')');
        
        if (nextFilter != null) {
            qp.append (" OR ");
            addSelectFilter (qp, part, nextFilter);
            qp.append (')');
        }

    }
    
    void addSelectFilters (QP qp, Part p) {
        
        for (int i = 0; i < p.getFilters ().size (); i ++) {

            if (i > 0) qp.append (" AND ");
            
            addSelectFilter (qp, p, (Filter) p.getFilters ().get (i));

        }

    }

    void addSelectJoin (QP qp, Join j) {
        qp.append (j.isInner () ? " INNER JOIN " : " LEFT JOIN ");
        String name = j.getTable ().getName ();
        qp.append (name);
        if (!j.getTableAlias ().equals (name)) {
            qp.append (' ');
            qp.append (j.getTableAlias ());
        }
        qp.append (" ON ");
        if (j.hasFilters ()) qp.append ('(');
        j.getJoinCondition ().add (qp);
        if (j.hasFilters ()) {
            qp.append (" AND ");
            addSelectFilters (qp, j);
            qp.append (')');
        }
    }

    void addSelectJoins (Select select, QP qp) {
        for (Join j: select.getJoins ())
            addSelectJoin (qp, j);
    } 
    
    void addSelectWhere (Select select, QP qp) {
        if (!select.hasFilters ()) return;
        qp.append (" WHERE ");
        addSelectFilters (qp, select);
    }
    
    void addSelectOrder (Select select, QP qp) {
        if (select.getOrder () == null) return;
        qp.append (" ORDER BY ");
        qp.append (select.getOrder ());
    }     
    
    void addSelectFrom (Select select, QP qp) {
        qp.append ("FROM ");
        final String name = select.getTable ().getName ();
        qp.append (name);
        final String tableAlias = select.getTableAlias ();
        if (!tableAlias.equals (name)) {
            qp.append (' ');
            qp.append (tableAlias);
        }
    }
    
    protected QP createPlainDeleteQP (Select select) {
        final QP qp = new QP ("DELETE FROM ");
        qp.append (select.getTable ().getName ());
        return qp;
    }

    protected QP createNestedDeleteQP (Select select) {
        final Table table = select.getTable ();
        final List<Col> pk = table.getPk ();
        if (pk.size () != 1) throw new IllegalArgumentException ("Nested DELETE for vector PK is not yet supported");
        final QP qp = new QP ("DELETE FROM ");
        qp.append (table.getName ());
        qp.append (" WHERE ");
        final String pkName = pk.get (0).getName ();
        qp.append (pkName);
        qp.append (" IN (SELECT ");
        qp.append (select.getTableAlias ());
        qp.append ('.');
        qp.append (pkName);
        qp.append (' ');
        return qp;
    }

    @Override
    public QP toDeleteQP (Select select) {
        
        if (select.getJoins ().isEmpty ()) {
            QP qp = createPlainDeleteQP (select);
            addSelectWhere   (select, qp);
            return qp;
        }
        else {
            QP qp = createNestedDeleteQP (select);
            addSelectFrom    (select, qp);
            addSelectJoins   (select, qp);
            addSelectWhere   (select, qp);
            qp.append (')');
            return qp;
        }

    }

    @Override
    public QP toQP (Select select) {        
        QP qp = createSelectQP ();
        addSelectColumns (select, qp);
        addSelectFrom    (select, qp);
        addSelectJoins   (select, qp);
        addSelectWhere   (select, qp);
        addSelectOrder   (select, qp);
        if (select.getLimit () != null) qp = toLimitedQP (qp, select.getOffset (), select.getLimit ());
        return qp;
    }
    
    @Override
    public QP toCntQP (Select select) {        
        QP qp = createSelectQP ();
        qp.append (" COUNT(*) ");
        addSelectFrom    (select, qp);
        for (Join j: select.getJoins ()) if (j.isInner ()) addSelectJoin (qp, j);
        addSelectWhere   (select, qp);
        return qp;
    }

    protected void setNullability (Table table, PhysicalCol col, String nn) throws SQLException {

        d0 (genNullableSql (table, col, nn));

    }
    
    protected void update (Table table, PhysicalCol toBe, TypeAction action) throws SQLException {
        
        if (action == null) return;
        
        switch (action) {
            case ALTER:
                alter (table, toBe);
                break;
            case RECREATE:
                recreate (table, toBe);
                break;
        }
        
    }
    
    public boolean equalDef (PhysicalCol asIs, PhysicalCol toBe) {
        
        String a = asIs.getDef ();
        String b = toBe.getDef ();
        
        if (a == null && b == null) return true;
        if (a == null && b != null) return false;
        if (a != null && b == null) return false;
        if (a.equals (b)) return true;
        
        return false;
        
    }

    protected void appendColDefaultExpression (QP qp, PhysicalCol col) {

        qp.append (" DEFAULT ");
        qp.append (col.getDef () == null ? "NULL" : col.getDef ());
        
    }
    
    protected void appendColDimension (QP qp, PhysicalCol col) {
        
        switch (col.getType ()) {
            case NUMERIC:
            case VARCHAR:
            case VARBINARY:
                qp.append ('(');
                qp.append (col.getLength ());
                break;
            default:
                return;
        }
        
        if (col.getType () == JDBCType.NUMERIC) {
            qp.append (',');
            qp.append (col.getPrecision ());
        }
        
        qp.append (')');
        
    }

    protected void appendColDefinition (QP qp, PhysicalCol col, boolean addNull) {
        
        qp.append (getTypeName (col));
        
        appendColDimension (qp, col);
        
        appendColDefaultExpression (qp, col);
        
        if (addNull && !col.isNullable ()) qp.append (" NOT NULL");

    }
    
    protected void adjustNullability (Table table, PhysicalCol col, NullAction act) throws SQLException {
        
        if (act == null) return;
                
        switch (act) {
            case SET:
                setNullability (table, col, "NULL");
                break;
            case UNSET:
                setNotNull (table, col);
                break;
        }
        
    }    

    private void setNotNull (Table table, PhysicalCol col) throws SQLException {
        
        if (col.getDef () == null) {
            logger.warning ("Cannot SET NOT NULL " + table.getName () + '.' + col.getName () + ", missing DEFAULT");
            return;
        }
        
        fillInNulls (table, col);
        
        setNullability (table, col, "NOT NULL");
        
    }

    protected void update (Table table, PhysicalCol asIs, PhysicalCol toBe) throws SQLException {
        
        Diff diff = new Diff (asIs, toBe, this);
                
        adjustNullability (table, toBe, diff.getNullAction ());

        update (table, toBe, diff.getTypeAction ());

        if (diff.isCommentChanged ()) comment (table, toBe);

    }

    protected void fillInNulls (Table table, PhysicalCol col) throws SQLException {
        
        StringBuilder sb = new StringBuilder ("UPDATE ");
        sb.append (table.getName ());
        sb.append (" SET ");
        sb.append (col.getName ());
        sb.append (" = ");
        sb.append (col.getDef ());
        sb.append (" WHERE ");
        sb.append (col.getName ());
        sb.append (" IS NULL");
        
        d0 (sb.toString ());
        
    }
   
    protected void setPk (Table table) throws SQLException {        
        d0 (genSetPkSql (table));
    }

    protected final void create (Table table, List<Ref> newRefs) throws SQLException {

        logger.fine ("Creating " + table.getName ());

        d0 (genCreateSql (table));

        setPk (table);

        comment (table);

        for (Col col: table.getColumns ().values ()) {

            if (col instanceof Ref) newRefs.add ((Ref) col);

            comment (table, col);

        }

    }

    protected void create (Table table, PhysicalKey key) throws SQLException {
        
        logger.fine ("Creating key " + key + " on " + table.getName ());

        d0 (genCreateSql (table, key));

    }
    
    protected void recreate (Table table, PhysicalKey key) throws SQLException {
        
        logger.fine ("Rereating key " + key + " on " + table.getName ());

        d0 (genDropSql (table, key));
        d0 (genCreateSql (table, key));

    }

    protected void create (Table table, PhysicalCol col) throws SQLException {
        
        logger.fine ("Creating " + table.getName () + '.' + col.getName ());
                
        d0 (genCreateSql (table, col));
        
        comment (table, col);
        
    }
    
    protected void create (Ref ref) throws SQLException {
        
        final QP qp = genCreateSql (ref);
        
        if (qp == null) return;

        logger.fine ("Creating FK for " + ref.getTable ().getName () + '.' + ref.getName ());
                
        d0 (qp);
                
    }

    protected void alter (Table table, PhysicalCol col) throws SQLException {
        
        logger.fine ("Altering " + table.getName () + '.' + col.getName ());
                
        d0 (genAlterSql (table, col));
                
    }
    
    private void update (PhysicalTable oldTable, Table newTable, Col toBe, List<Ref> newRefs) throws SQLException {
        
        PhysicalCol asIs = (PhysicalCol) oldTable.getColumns ().get (toBe.getName ());
        
        if (asIs == null) {
            
            create (newTable, toBe.toPhysical ());
            
            if (toBe instanceof Ref) newRefs.add ((Ref) toBe);
            
        } else {

            update (newTable, asIs, toBe.toPhysical ());

        }

    }    

    private void update (PhysicalTable oldTable, Table newTable, List<Ref> newRefs) throws SQLException {

        final Collection<Col> cols = newTable.getColumns ().values ();

        for (Col toBe: cols) if (!toBe.toPhysical ().isVirtual ()) update (oldTable, newTable, toBe, newRefs);
        for (Col toBe: cols) if ( toBe.toPhysical ().isVirtual ()) update (oldTable, newTable, toBe, newRefs);
                    
        if (!oldTable.getRemark ().equals (newTable.getRemark ())) comment (newTable);
        
    }
        
    private final void updateData (Table t) throws SQLException {
    
        List<Map <String, Object>> data = t.getData ();
        
        if (data.isEmpty ()) return;
        
        upsert (t, data);
        
    }
    
    private static final Pattern RE_FIELD = Pattern.compile ("(\"[A-Za-z_][A-Za-z0-9_]*\")");
    
    @Override
    public final void adjustTable (Table t) {
        
        t.setModel (model);

        Map<String, PhysicalCol> physicalVirtualCols = new HashMap<> ();

        for (Col c: t.getColumns ().values ()) {
            c.setTable (t);
            adjustCol (c);
            PhysicalCol phy = c.toPhysical ();
            if (phy.isVirtual ()) physicalVirtualCols.put ('"' + phy.getName ().toUpperCase () + '"', phy);
        }

        int tries = physicalVirtualCols.size ();

        for (int n = 0; n < tries; n ++) {

            boolean found = false;

            for (PhysicalCol v: physicalVirtualCols.values ()) {

                String def = v.getDef ();

                Matcher m = RE_FIELD.matcher (def);
                
                while (m.find ()) {
                    String key = m.group ().toUpperCase ();
                    if (!physicalVirtualCols.containsKey (key)) continue;
                    def = def.replace (m.group (), physicalVirtualCols.get (key).getDef ());
                    found = true;
                }

                if (!found) continue;

                logger.info (t.getName () + '.' + v.getName () + ": " + v.getDef () + " .. " + def);

                v.setDef (def);

            }

            if (!found) break;

        }

    }

    private final void adjustCol (Col col) {
        col.setPhysicalCol (toPhysical (col));
    }

    @Override
    public PhysicalCol toPhysical (Col col) {
        
        Col canonical = toCanonical (col);
        
        PhysicalCol physical = toBasicPhysical (canonical);
        
        physical.setRemark   (canonical.getRemark  ());
        physical.setNullable (col.isNullable ());
        
        adjustDefaultValue (col, physical);

        return physical;
        
    }

    protected void adjustDefaultValue (Col col, PhysicalCol physical) {
        
        final Def def = col.getDef ();
        
        if (def == null) return;
            
        if (def instanceof Virt) {
                physical.setDef (((Virt) def).getExpression ());
                physical.setVirtual (true);
                physical.setNullable (true);
        }
        else {
                physical.setDef (toSQL (def, physical.getType ()));
        }
        
    }
    
    public void adjustModel () {
        
        for (Table t: model.getTables ()) {
            t.setModel (model);
            adjustTable (t);
        }
        
    }
    
    @Override
    public void updateSchema (Table... wishes) throws SQLException {
        updateSchema (Arrays.asList (wishes));
    }
    
    public void updateSchema (Collection<Table> tv) throws SQLException {
        
        PhysicalModel ex = getExistingModel ();

        List<Table> tables = new ArrayList<> ();
        List<View>  views  = new ArrayList<> ();
        List<Ref> newRefs  = new ArrayList<> ();
        
        for (Table i: tv) if (i instanceof View) views.add ((View) i); else tables.add (i);
                
        for (Table toBe: tables) {            
            PhysicalTable asIs = ex.get (toBe.getName ());            
            if (asIs == null) create (toBe, newRefs); else update (asIs, toBe, newRefs);                                    
        }
        
        addIndexes (ex);
        
        for (Table toBe: tables) {
            
            PhysicalTable asIs = ex.get (toBe.getName ());
            
            for (Key k: toBe.getKeys ().values ()) {

                PhysicalKey keyToBe = toPhysical (toBe, k);

                PhysicalKey keyAsIs = asIs == null ? null : (PhysicalKey) asIs.getKeys ().get (keyToBe.getName ());
                
                if (keyAsIs == null) {

                    create (toBe, keyToBe);

                }
                else if (!keyAsIs.equals (keyToBe)) {

                    recreate (toBe, keyToBe);

                }                
                
            }
            
        }                
        
        for (Table t: tables) updateData (t);        
        
        updateViews (views);        
        
        for (Ref ref: newRefs) create (ref);
        
        for (Table t: tables) for (Trigger trg: t.getTriggers ().values ()) update (t, trg);

        checkModel ();

    }

    private void updateViews (List<View> views) throws SQLException {
        
        int tries = views.size ();
        
        if (tries == 0) return;
        
        SQLException lastException = null;
        
        Set<View> passed = new HashSet (tries);
        
        for (int i = 0; i < tries; i ++) {
            
            lastException = null;

            for (View v: views) {
                
                if (passed.contains (v)) continue;
                
                try {
                    update (v);
                    passed.add (v);
                }
                catch (SQLException e) {
                    logger.warning ("Exception occured, will retry with " + v.getName () + ". The message was " + e.getMessage ());
                    lastException = e;                    
                }
                
            }

            if (lastException == null) return;
            
        }
        
        throw lastException;
        
    }

    public void updateSchema () throws SQLException {
        updateSchema (model.getTables ());
    }
    
    protected void checkModel () throws SQLException {}
    
    protected abstract PhysicalKey toPhysical (Table table, Key k);
    protected abstract Col toCanonical (Col col);
    protected abstract String toSQL (Def def, JDBCType type);
    protected abstract PhysicalCol toBasicPhysical (Col col);
    protected abstract PhysicalModel getExistingModel () throws SQLException;
    protected abstract QP genCreateSql (Table table) throws SQLException;
    protected abstract QP genSetPkSql (Table table);
    protected abstract QP genCreateSql (Table table, PhysicalCol col) throws SQLException;
    protected abstract QP genCreateSql (Table table, PhysicalKey key) throws SQLException;
    protected abstract QP genDropSql (Table table, PhysicalKey key) throws SQLException;
    protected abstract QP genCreateSql (Ref ref) throws SQLException;
    protected abstract QP genAlterSql  (Table table, PhysicalCol col) throws SQLException;
    protected abstract QP genNullableSql (Table table, PhysicalCol col, String nn) throws SQLException;
    protected abstract void comment (Table table) throws SQLException;
    protected abstract void comment (Table table, AbstractCol col) throws SQLException;
    protected abstract void recreate (Table table, PhysicalCol col) throws SQLException;
    protected abstract String getTypeName (PhysicalCol col);
    protected abstract void update (View view) throws SQLException;
    protected abstract void update (Table table, Trigger trg) throws SQLException;
    protected abstract void addIndexes (PhysicalModel m) throws SQLException;
    
}