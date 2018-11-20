package ru.eludia.base.db.sql.gen;

import java.util.Collections;
import java.util.StringTokenizer;
import java.util.logging.Logger;
import ru.eludia.base.DB;
import ru.eludia.base.db.sql.build.QP;
import ru.eludia.base.model.phys.PhysicalCol;

/**
 * Вычислитель условия, входящего в сотав фильтра для Select: 
 * оператор + параметры
 */
public class Predicate {
    
    Operator operator;
    boolean not    = false;
    boolean orNull = false;
    Object [] values;

    public Predicate (Operator operator, boolean not, boolean orNull, Object [] values) {
        this.operator = operator;
        this.not = not;
        this.orNull = orNull;
        this.values = values;
    }

    public Predicate (Operator operator, Object[] values) {
        this.operator = operator;
        this.values = values;
    }

    public boolean isOrNull () {
        return orNull;
    }
    
    private final static Logger logger = Logger.getLogger (Predicate.class.getName ());

    private void parseIs (String src) {
        
        StringTokenizer st = new StringTokenizer (src);

        if (!st.hasMoreElements ()) throw new IllegalArgumentException ("Unfinished IS in expression " + src);
            
        String nn = st.nextToken ();
            
        if ("NULL".equals (nn) && !st.hasMoreElements ()) return;
            
        if (!"NOT".equals (nn) || !st.hasMoreElements () || !"NULL".equals (st.nextToken ())) throw new IllegalArgumentException ("Invalid expression " + src);        
            
        not = true;
        
    }

    private void parse (String src) {
        
        if (src == null) src = "";
        
        if (src.startsWith ("...")) {
            orNull = true;
            parse (src.substring (3));
            return;
        }
        
        if (src.startsWith ("IS ")) {
            operator = Operator.IS_NULL;
            parseIs (src.substring (3));
            return;
        }

        if (src.startsWith ("NOT ")) {
            not = true;
            parse (src.substring (4));
            return;
        }

        StringTokenizer st = new StringTokenizer (src);
        
        if (!st.hasMoreElements ()) {
            
            if (values.length > 0 && (values [0] instanceof Select || values [0] instanceof QP)) {
                operator = Operator.IN;
            }
            else {
                operator = Operator.EQ;
            }
            
            return;
            
        }
        
        String op = st.nextToken ();
                
        switch (op) {
            case "=":
                operator = Operator.EQ;
                break;
            case "<>":
                operator = Operator.EQ;
                not = !not;
                break;
            case "<":
                operator = Operator.LT;
                break;
            case "<=":
                operator = Operator.GT;
                not = !not;
                break;
            case ">":
                operator = Operator.GT;
                break;
            case ">=":
                operator = Operator.LT;
                not = !not;
                break;
            case "LIKE":
                operator = Operator.LIKE;
                break;
            case "BETWEEN":
                operator = Operator.BETWEEN;
                break;
            case "IN":
                operator = Operator.IN;
                break;
            default:
                throw new IllegalArgumentException ("Invalid operator " + op + " in expression " + src);
        }

        switch (operator) {
            case IN:
                break;
            case IS_NULL:
                if (values.length > 0) throw new IllegalArgumentException ("No parameter can be set for IS [NOT] NULL predicate");
                break;
            case BETWEEN:
                if (values.length != 2) throw new IllegalArgumentException ("Exactly 2 parameters must be set for BETWEEN predicate");
                break;
            default:
                if (values.length != 1) throw new IllegalArgumentException ("Exactly 1 parameter must be set for " + operator + " predicate");
                break;
        }
        
        if (isOff ()) return;
        
        int i = 0;
        
        while (st.hasMoreTokens ()) {
            
            switch (st.nextToken ()) {
                case "?":
                    break;
                case "%?":
                    values [i] = "%" + values [i];
                    break;
                case  "?%":
                    values [i] =       values [i] + "%";
                    break;
                case "%?%":
                    values [i] = "%" + values [i] + "%";
                    break;
                default:
                    continue;
            }

            i ++;
            
        }

    }
    
    public Predicate (String src, Object[] values) {        
        this.values = values == null ? Collections.EMPTY_LIST.toArray () : values;
        parse (src);
    }
    
    /**
     * Признак того, что фильтр с данным условием следует
     * игнорировать при генерации SQL.
     * 
     * В Web-интерфейсах в поисковых запросах пустое значение параметра обычно 
     * означает отсутствие ограничения на соответствующее поле.
     * 
     * @return true, если число параметров не 0 (то есть оператор не IS NULL) 
     * и 1-й параметр имеет значение null.
     */
    public final boolean isOff () {
        
        switch (operator) {
            case IS_NULL:
                return false;
            default:
                return values.length == 0 || values [0] == null;
        }
        
    } 

    /**
     * Признак того, что результат getSQL () нужно обернуть в NOT (...)
     * @return правда ли, что выражение следует понимать наоборот.
     */
    public boolean isNot () {
        return not;
    }

    /**
     * Опреатор данного условия
     * @return что есть
     */
    public Operator getOperator () {
        return operator;
    }

    /**
     * Значения параметров, входящие в условие.
     * 
     * По большей части совпадают с переданными в конструктор, но 
     * для выражений '%?%' и '?%' исправляются соответственно.
     * 
     * @return Список для подстановки в PreparedStatement.
     */
    public Object[] getValues () {
        return values;
    }
    
    /**
     * Приписывание фрагмента SQL к буферу.
     * @param qp буфер
     * @param col описание поля, к которому относится данное условие
     */
    public void appendTo (QP qp, PhysicalCol col, DB db) {
        
        switch (operator) {
            case IS_NULL:
                qp.append (" IS NULL");
                break;
            case EQ:
                qp.add ("=?", values [0], col);
                break;
            case GT:
                qp.add (">?", values [0], col);
                break;
            case LT:
                qp.add ("<?", values [0], col);
                break;
            case LIKE:
                qp.add (" LIKE ?", values [0], col);
                break;
            case BETWEEN:
                qp.add (" BETWEEN ?", values [0], col);
                qp.add (" AND ?", values [1], col);
                break;
            case IN:
                qp.append (" IN(");
                if (values.length == 1 && values [0] instanceof Select) {
                    qp.add (db.toQP ((Select) values [0]));
                    qp.append (')');
                }
                else if (values.length == 1 && values [0] instanceof QP) {
                    qp.add ((QP) values [0]);
                    qp.append (')');
                }
                else {
                    for (int i = 0; i < values.length; i++) qp.add ("?,", values [i], col);
                }
                qp.setLastChar (')');
        }
        
    }
    
}