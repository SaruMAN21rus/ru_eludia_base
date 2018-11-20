package ru.eludia.base.db.sql.build;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import ru.eludia.base.model.phys.PhysicalCol;
import ru.eludia.base.db.util.ParamSetter;

/**
 * Общий предок классов, используемых для сборки SQL с параллельным 
 * формированием списка параметров.
 * 
 * Это аналог стандартных StringBuilder, JsonObjectBuilder и т. п.,
 * только для SQL-запросов.
 */
public abstract class SQLBuilder {
    
    static final int MAX_LOG_PARAM_LEN = 40;

    StringBuilder sb = new StringBuilder ();
    StringBuilder psb = new StringBuilder ();
    List <PhysicalCol> cols;
    
    final Logger logger = Logger.getLogger (this.getClass ().getName ());

    public SQLBuilder () {
    }
    
    public SQLBuilder (String sql) {
        sb.append (sql);
    }
    
    public boolean isToPrepare () {
        return true;
    }
        
    /**
     * Добавление одиночного символа к формируемому SQL.
     * @param c добавляемый символ
     */
    public final void append (char c) {
        sb.append (c);        
    }
    
    /**
     * Добавление литерала числа к формируемому SQL.
     * 
     * НЕ предназначен для добавления параметров запросов. Используется только 
     * при формировании DDL.
     * 
     * @param c число (размерность поля в ALTER TABLE и т. п.)
     */
    public final void append (int c) {
        sb.append (c);        
    }
    
    /**
     * Добавление произвольной строки к формируемому SQL.
     * @param sql фрагмент SQL
     */
    public final void append (String sql) {
        sb.append (sql);        
    }
    
    /**
     * Добавление к формируемому SQL списка строк через запятую.
     * @param terms фрагменты SQL
     */
    public final void appendWithCommas (String... terms) {
        
        int last = terms.length - 1;
        
        if (last < 0) return;
        
        for (int i = 0; i < last; i ++) {
            append (terms [i]);
            append (',');
        }        
        
        append (terms [last]);
        
    }
    
    /**
     * Добавление строки к формируемому SQL строки с запоминанием поля таблицы.
     * 
     * @param sql Фрагмент SQL с единственным '?'
     * @param col поле с типом соответствующего параметра.
     */
    public final void append (String sql, PhysicalCol col) {
                
        sb.append (sql);
        
        if (cols == null) cols = new ArrayList <> ();
        
        cols.add (col);
                
    }
        
    /**
     * Последний символ в текущем буфере
     * @return subj
     */
    public char getLastChar () {
        return sb.charAt (sb.length () - 1);
    }    
    
    /**
     * Накопленный SQL
     * @return содержимое буфера
     */
    public final String getSQL () {
        return sb.toString ();
    }
    
    /**
     * Список стоблцов, накопленный по ходу обработки вызовов append ()
     * @return Список стоблцов
     */
    public final List<PhysicalCol> getCols () {
        return cols;
    }
        
    /**
     * Подстановка накопленных значений параметров в предоставленный запрос.
     * @param st
     * @param ps
     * @throws SQLException
     */
    public abstract void setParams (PreparedStatement st, ParamSetter ps) throws SQLException;
    
    /**
     * Запуск предоставленного запроса (одиночный либо пакетный)
     * @param st
     * @throws SQLException
     */
    public void execute (PreparedStatement st) throws SQLException {
        st.execute ();
    }    
    
    public static void setLastChar (StringBuilder b, char c) {
        b.setCharAt (b.length () - 1, c);
    }
    
    /**
     * Подмена последнего символа в накопленном буфере SQL. 
     * 
     * Например, с ',' на ')' на конце списка.
     * 
     * @param c Символ, который требуется поставить в конец строки вместо текущего.
     */
    public final void setLastChar (char c) {
        setLastChar (sb, c);
    }
    
    final void openLogRecord () {
        psb.append ('[');
    }
    
    final void closeLogRecord () {
        setLastChar (psb, ']');
    }

    void logParam (Object p) {
                
        if (p == null) {
            psb.append ("NULL,");
            return;
        }

        if (p instanceof Number || p instanceof Boolean) {
            psb.append (p);
            psb.append (',');
            return;
        }

        String s = p.toString ();        
        boolean isLong = s.length () > MAX_LOG_PARAM_LEN;
        if (isLong) s = s.substring (0, MAX_LOG_PARAM_LEN - 1);
                
        psb.append ('"');
        
        for (int i = 0; i < s.length (); i ++) {
                
            char c = s.charAt (i);
                
            switch (c) {
                case '\n': 
                case '\r': 
                    psb.append (' ');
                    break;
                case '"': 
                    psb.append ('\'');
                default: 
                    psb.append (c);
            }                
            
        }

        psb.append (isLong ? "...\"," : "\",");
        
    }

    @Override
    public final String toString () {        
        
        final int length = psb.length ();
        
        return 
            length == 0 ? sb.toString () : 
            length > 10000 ? sb.toString () + "[LOTS OF PARAMS]" :
            sb + " " + psb;
        
    }
    
}