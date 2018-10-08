package ru.eludia.base;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import ru.eludia.base.db.sql.build.QP;
import ru.eludia.base.db.util.JDBCConsumer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import ru.eludia.base.db.sql.build.SQLBuilder;
import ru.eludia.base.db.sql.build.TableRecordSQLBuilder;
import ru.eludia.base.db.sql.build.TableRecordListBuilder;
import ru.eludia.base.db.sql.build.TableSQLBuilder;
import ru.eludia.base.Model;
import ru.eludia.base.model.phys.PhysicalCol;
import ru.eludia.base.model.abs.Roster;
import ru.eludia.base.model.Table;
import ru.eludia.base.db.sql.gen.Get;
import ru.eludia.base.db.sql.gen.Select;
import ru.eludia.base.db.util.ParamSetter;
import ru.eludia.base.db.util.TypeConverter;
import javax.xml.datatype.XMLGregorianCalendar;
import ru.eludia.base.model.Col;

/**
 * Обёртка над JDBC Connection, через которую доступна большая часть API БД Dia.java.
 * 
 * Экземпляры этого класса следует получать тольо от Model методом getDb ()
 * и только в рамках блоков try-with:
 * 
 *     try (DB db = model.getDb ()) {
 *          ...
 *     }
 * 
 */
public abstract class DB implements AutoCloseable, ParamSetter {
    
    protected final Logger logger = Logger.getLogger (this.getClass ().getName ());
    
    /**
     * Набор вспомогательных процедур по переводу типов данных, доступный в виде
     * статической переменной.
     */
    public static TypeConverter to = new TypeConverter ();

    protected Connection cn;
    protected Model model;

    /**
     * Редко используемый метод для получения вложенного JDBC Connection.
     * Нужен для доступа к полному API JDBC, без обёрток. 
     * @return JDBC Connection к основной БД приложения
     */
    public final Connection getConnection () {
        return cn;
    }

    public Model getModel () {
        return model;
    }
    
    @Override
    public void close () {
        try {
            if (!cn.getAutoCommit ()) cn.rollback ();
            cn.close ();
        }
        catch (SQLException ex) {
            throw new IllegalStateException (ex);
        }
    }    
    
    /**
     * Начало транзакции.
     * @throws SQLException
     */
    public final void begin () throws SQLException {
        cn.setAutoCommit (false);
    }
    
    /**
     * Подтверждение транзакции
     * @throws SQLException
     */
    public final void commit () throws SQLException {
        cn.commit ();
    }
    
    /**
     * Откат транзакции
     * @throws SQLException
     */
    public final void rollback () throws SQLException {
        cn.rollback ();
    }
    
    /**
     * Конструктор хэш-таблицы, компенсирующий отсутствие в java
     * синтаксиса {k: v, ...}:
     * 
     *  Map<String, Object> rec = HASH (
     *    id, 1, 
     *    label, "test"
     *  );
     * 
     * @param o список объектов, где через один идут ключи и соответствующие им значения
     * @return хэш из всего переданного
     */
    public static Map<String, Object> HASH (Object... o) {
        
        int len = o.length;
        
        Map <String, Object> m = new HashMap (len >> 1);
        
        for (int i = 0; i < len; i += 2) m.put (o [i].toString (), o [i + 1]);
        
        return m;
        
    }
    
    /**
     * Конструктор хэша из очередной записи заранее открытого RecordSet'а.
     * Ключи -- естественно, имена полей.
     * Значения -- то, что вернёт getValue ().
     * @param rs курсор, для которого уже вызван next (), причём успешно
     * @return запись в виде хэша.
     * @throws SQLException
     */
    public Map<String, Object> HASH (ResultSet rs) throws SQLException {
        
        ResultSetMetaData metaData = rs.getMetaData ();
        
        final int cnt = metaData.getColumnCount ();
        
        Map <String, Object> m = new HashMap (cnt);
        
        for (int n = 1; n <= cnt; n ++) 
            if (metaData.getColumnType (n) != java.sql.Types.BLOB) 
                m.put (metaData.getColumnName (n), getValue (rs, n));
        
        return m;
        
    }    
    
    /**
     * Не надо вызывать этот конструктор. Используйте model.getDb ()
     * @param cn JDBC Connection
     * @param model модель
     */
    public DB (Connection cn, Model model) {
        this.cn = cn;
        this.model = model;
    }

    /**
     * Не надо вызывать этот метод. Используйте model.getDb ()
     * @param cn JDBC Connection
     * @param model модель
     */
    public static DB DB (Connection cn, Model model) throws SQLException {
        
        if (cn == null) throw new IllegalArgumentException ("null connection passed");
        
        String product = cn.getMetaData ().getDatabaseProductName ();
        
        switch (product) {
            
            case "Oracle": return new ru.eludia.base.db.dialect.Oracle (cn, model);
        
            default: throw new IllegalArgumentException (product + " not supported");
        
        }

    }
    
    /**
     * Вызов DML/DDL-запроса с заданным списком параметров.
     * Если этот список пуст, исполняется createStatement, иначе -- prepareStatement
     * @param sql Не-SELECT запрос
     * @param params Значения параметров в виде java-объектов более-менее подходящих классов.
     * @throws SQLException
     */
    public final void d0 (String sql, List<Object> params) throws SQLException { d0 (new QP (sql, params)); }
    
    /**
     * Вызов DML/DDL-запроса с заданным списком параметров.
     * Если этот список пуст, исполняется createStatement, иначе -- prepareStatement
     * @param sql Не-SELECT запрос
     * @param params Значения параметров в виде java-объектов более-менее подходящих классов.
     * @throws SQLException
     */
    public final void d0 (String sql, Object... params) throws SQLException {d0 (new QP (sql, params));}
    
    /**
     * Пакетное добавление записей в таблицу
     * @param c Класс описания таблицы
     * @param records Пакет записей
     * @throws SQLException
     */
    public final void insert (Class c, List<Map <String, Object>> records) throws SQLException {
        insert (model.get (c), records);
    }    
    
    /**
     * Пакетное добавление записей в таблицу
     * @param t Описание таблицы
     * @param records Пакет записей
     * @throws SQLException
     */
    public final void insert (Table t, List<Map <String, Object>> records) throws SQLException {        
        if (records == null || records.isEmpty ()) return;       
        TableRecordListBuilder b = new TableRecordListBuilder (t, records);
        genInsertSql (b);
        d0 (b);        
    }
    
    /**
     * Синхронизация отдельной записи данных по первичному ключу.
     * @param c Класс описания таблицы
     * @param record Хэш со значениями полей. Первичный ключ должен быть указан, остальные -- не обязательно.
     * @throws SQLException
     */
    public final void upsert (Class c, Map <String, Object> record) throws SQLException {
        upsert (c, record, null);
    }            
    
    /**
     * Синхронизация отдельной записи данных по заданному ключу.
     * @param c Класс описания таблицы
     * @param record Хэш со значениями полей. Ключевые должны быть указаны, остальные -- не обязательно.
     * @param key Список имён полей, составляющих ключ синхронизации. Может быть null или пустым -- тогда используется первичный ключ.
     * @throws SQLException
     */
    public final void upsert (Class c, Map <String, Object> record, String... key) throws SQLException {
        upsert (model.get (c), record, key);
    }            
    
    /**
     * Синхронизация отдельной записи данных по заданному ключу.
     * @param t Описание таблицы
     * @param record Хэш со значениями полей. Ключевые должны быть указаны, остальные -- не обязательно.
     * @param key Список имён полей, составляющих ключ синхронизации. Может быть null или пустым -- тогда используется первичный ключ.
     * @throws SQLException
     */
    public final void upsert (Table t, Map <String, Object> record, String... key) throws SQLException {
                                
        if (key == null || key.length == 0) key = (String []) t.getPkColNames ();
        
        Roster<PhysicalCol> keyCols = new Roster<> ();        
        for (String name: key) keyCols.add (t.getColumn (name).toPhysical ());
                
        TableRecordSQLBuilder b = new TableRecordSQLBuilder (t, record);
        genUpsertSql (b, keyCols);
        d0 (b);        
        
    }

    /**
     * Синхронизация пакета записей по заданному ключу БЕЗ УДАЛЕНИЯ.
     * 
     * После исполнения этого метода для каждого элемента списка будет либо создана
     * новая запись, либо обновлена ранее существовавшая.
     * 
     * Остальные записи при этом не затрагиваются.
     * 
     * @param c Класс описания таблицы
     * @param records Хэш со значениями полей. Ключевые должны быть указаны, остальные -- не обязательно.
     * @param key Список имён полей, составляющих ключ синхронизации. Может быть null или пустым -- тогда используется первичный ключ.
     * @throws SQLException
     */
    public final void upsert (Class c, List<Map <String, Object>> records, String... key) throws SQLException {
        upsert (model.get (c), records, key);
    }            

    /**
     * Синхронизация пакета записей по заданному ключу БЕЗ УДАЛЕНИЯ.
     * 
     * После исполнения этого метода для каждого элемента списка будет либо создана
     * новая запись, либо обновлена ранее существовавшая.
     * 
     * Остальные записи при этом не затрагиваются.
     * 
     * @param t Описание таблицы
     * @param records Список хэшей со значениями полей. Ключевые должны быть указаны, остальные -- не обязательно.
     * @param key Список имён полей, составляющих ключ синхронизации. Может быть null или пустым -- тогда используется первичный ключ.
     * @throws SQLException
     */
    public final void upsert (Table t, List<Map <String, Object>> records, String... key) throws SQLException {
        
        if (records == null || records.size () == 0) return;
                        
        if (key == null || key.length == 0) key = t.getPkColNames ();
        
        Roster<PhysicalCol> keyCols = new Roster<> ();        
        for (String name: key) keyCols.add (t.getColumn (name).toPhysical ());
                
        TableRecordListBuilder b = new TableRecordListBuilder (t, records);
        genUpsertSql (b, keyCols);
        d0 (b);        
        
    }  
    
    /**
     * Синхронизация пакета записей по заданному ключу БЕЗ УДАЛЕНИЯ.
     * 
     * После исполнения этого метода для каждого элемента списка будет либо создана
     * новая запись, либо обновлена ранее существовавшая.
     * 
     * Остальные записи при этом не затрагиваются.
     * 
     * @param t Описание таблицы
     * @param records Поток хэшей со значениями полей. Ключевые должны быть указаны, остальные -- не обязательно.
     * @param key Список имён полей, составляющих ключ синхронизации. Может быть null или пустым -- тогда используется первичный ключ.
     * @throws SQLException
     */
    public final void upsert (Class t, Stream<Map <String, Object>> records, String... key) throws SQLException {
        upsert (t, records.collect (Collectors.toList ()), key);
    }

    /**
     * Синхронизация пакета записей по заданному ключу БЕЗ УДАЛЕНИЯ.
     * 
     * После исполнения этого метода для каждого элемента списка будет либо создана
     * новая запись, либо обновлена ранее существовавшая.
     * 
     * Остальные записи при этом не затрагиваются.
     * 
     * @param t Описание таблицы
     * @param records Поток хэшей со значениями полей. Ключевые должны быть указаны, остальные -- не обязательно.
     * @param key Список имён полей, составляющих ключ синхронизации. Может быть null или пустым -- тогда используется первичный ключ.
     * @throws SQLException
     */
    public final void upsert (Table t, Stream<Map <String, Object>> records, String... key) throws SQLException {
        upsert (t, records.collect (Collectors.toList ()), key);
    }

    /**
     * Синхронизация пакета записей по заданному ключу БЕЗ УДАЛЕНИЯ.
     * 
     * После исполнения этого метода для каждой записи, находящейся в таблице 
     * records, будет либо создана новая запись t, либо обновлена ранее 
     * существовавшая.
     * 
     * Остальные записи t при этом не затрагиваются.
     * 
     * @param t Описание таблицы, в которую требуется синхронизировать данные
     * @param records Описание таблицы-источника данных
     * @param key Список имён полей, составляющих ключ синхронизации. Может быть null или пустым -- тогда используется первичный ключ records.
     * @throws SQLException
     */
    public final void upsert (Table t, Table records, String... key) throws SQLException {
        
        if (key == null || key.length == 0) key = t.getPkColNames ();
        
        d0 (genUpsertSql (t, records, key));
        
    }      
    
    /**
     * Синхронизация пакета записей по заданному ключу БЕЗ УДАЛЕНИЯ.
     * 
     * После исполнения этого метода для каждой записи, находящейся в таблице 
     * records, будет либо создана новая запись t, либо обновлена ранее 
     * существовавшая.
     * 
     * Остальные записи t при этом не затрагиваются.
     * 
     * @param t Описание таблицы, в которую требуется синхронизировать данные
     * @param records Описание таблицы-источника данных
     * @param key Список имён полей, составляющих ключ синхронизации. Может быть null или пустым -- тогда используется первичный ключ records.
     * @throws SQLException
     */
    public final void upsert (Class t, Class records, String... key) throws SQLException {
        
        upsert (model.get (t), model.get (records), key);
        
    }
    
    /**
     * Быстрое удаление всех записей в таблице
     * @param t Описание таблицы
     * @throws SQLException
     */
    public final void truncate (Class t) throws SQLException {
                
        truncate (model.get (t));
        
    }      

    /**
     * Быстрое удаление всех записей в таблице
     * @param t Описание таблицы
     * @throws SQLException
     */
    public final void truncate (Table t) throws SQLException {
                
        d0 (genTruncateSql (t));
        
    }      
        
    /**
     * Синхронизация пакета записей по заданному ключу С УДАЛЕНИЕМ ЛИШНИХ ЗАПИСЕЙ.
     * 
     * После исполнения этого метода для каждого элемента списка будет либо создана
     * новая запись, либо обновлена ранее существовавшая.
     * 
     * А все записи с полями commonPart и значениями поля key, не 
     * встречающимимся в records, будут удалены (оператором DELETE).
     * 
     * @param t Описание таблицы
     * @param commonPart Хэш со значениями полей, общими для всего пакета записей. Там не может быть поля с именем, указанным параметром key.
     * @param records Список хэшей со значениями прочих полей записей. В каждом из них длч поля с именем, указанным параметром key, должно быть уникальное значение.
     * @param key Имя поля, играющего роль уникального ключа внутри подмножества записей t со значениями полей commonPart.
     * @throws SQLException
     */
    public final void dupsert (Class t, Map <String, Object> commonPart, List<Map <String, Object>> records, String key) throws SQLException {
        DB.this.dupsert (model.get (t), commonPart, records, key);
    }
    
    /**
     * Синхронизация пакета записей по заданному ключу С УДАЛЕНИЕМ ЛИШНИХ ЗАПИСЕЙ.
     * 
     * После исполнения этого метода для каждого элемента списка будет либо создана
     * новая запись, либо обновлена ранее существовавшая.
     * 
     * А все записи с полями commonPart и значениями поля key, не 
     * встречающимимся в records, будут удалены (оператором DELETE).
     * 
     * @param t Описание таблицы
     * @param commonPart Хэш со значениями полей, общими для всего пакета записей. Там не может быть поля с именем, указанным параметром key.
     * @param records Список хэшей со значениями прочих полей записей. В каждом из них длч поля с именем, указанным параметром key, должно быть уникальное значение.
     * @param key Имя поля, играющего роль уникального ключа внутри подмножества записей t со значениями полей commonPart.
     * @throws SQLException
     */
    public final void dupsert (String t, Map <String, Object> commonPart, List<Map <String, Object>> records, String key) throws SQLException {
        DB.this.dupsert (model.get (t), commonPart, records, key);
    }
    
    /**
     * Синхронизация пакета записей по заданному ключу С УДАЛЕНИЕМ ЛИШНИХ ЗАПИСЕЙ.
     * 
     * После исполнения этого метода для каждого элемента списка будет либо создана
     * новая запись, либо обновлена ранее существовавшая.
     * 
     * А все записи с полями commonPart и значениями поля key, не 
     * встречающимимся в records, будут удалены (оператором DELETE).
     * 
     * @param t Описание таблицы
     * @param commonPart Хэш со значениями полей, общими для всего пакета записей. Там не может быть поля с именем, указанным параметром key.
     * @param records Список хэшей со значениями прочих полей записей. В каждом из них длч поля с именем, указанным параметром key, должно быть уникальное значение.
     * @param key Имя поля, играющего роль уникального ключа внутри подмножества записей t со значениями полей commonPart.
     * @throws SQLException
     */
    public final void dupsert (Table t, Map <String, Object> commonPart, List<Map <String, Object>> records, String key) throws SQLException {

        List<Object> ids = new ArrayList ();

        for (Map <String, Object> record: records) {
            ids.add (record.get (key));
            record.putAll (commonPart);
        }

        List<String> uk = new ArrayList<> (commonPart.size () + 1);
        uk.add (key);

        Select selectToDelete = new Select (t, key).where (key + " NOT IN", ids.toArray ());
 
        commonPart.forEach ((k, v) -> {
            uk.add (k);
            selectToDelete.and (k, v);
        });

        delete (selectToDelete);
        upsert (t, records, uk.toArray (new String [0]));
        
    }
    
    /**
     * Обновление пакета записей по первичному ключу.
     * 
     * Для каждого элемента records в таблице t должна заранее существовать запись 
     * с соответствующим значением первичного ключа.
     * 
     * Если требуется досоздавать записи по мере необходимости, следует использовать [d]upsert.
     * 
     * @param t Описание таблицы
     * @param records Список обновляемых записей.
     * @throws SQLException
     */
    public final void update (Table t, List<Map <String, Object>> records) throws SQLException {        
        if (records == null || records.size () == 0) return;
        TableRecordListBuilder b = new TableRecordListBuilder (t, records);
        genUpdateSql (b);
        d0 (b);        
    }

    /**
     * Обновление пакета записей по первичному ключу.
     * 
     * Для каждого элемента records в таблице t должна заранее существовать запись 
     * с соответствующим значением первичного ключа.
     * 
     * Если требуется досоздавать записи по мере необходимости, следует использовать [d]upsert.
     * 
     * @param c Описание таблицы
     * @param records Список обновляемых записей. У каждой из них должен быть 
     * @throws SQLException
     */
    public final void update (Class c, List<Map <String, Object>> records) throws SQLException {
        update (model.get (c), records);
    }

    /**
     * Обновление отдельной записи по первичному ключу.
     *
     * @param t Описание таблицы
     * @param record Обновляемая запись. Первичный ключ должен быть задан.
     * @throws SQLException
     */
    public final void update (Table t, Map <String, Object> record) throws SQLException {
        for (Col pkCol: t.getPk ()) {
            String name = pkCol.getName ();
            if (!record.containsKey (name)) throw new IllegalArgumentException ("PK " + name + " not set for " + t.getName () + ": " + record);
        }
        TableRecordSQLBuilder b = new TableRecordSQLBuilder (t, record);
        genUpdateSql (b);
        d0 (b); 
    }
    
    /**
     * Обновление отдельной записи по первичному ключу.
     *
     * @param c Класс с описанием таблицы
     * @param record Обновляемая запись. Первичный ключ должен быть задан.
     * @throws SQLException
     */
    public final void update (Class c, Map <String, Object> record) throws SQLException {
        update (model.get (c), record);
    }    
    
    /**
     * Удаление множества записей одной таблицы по заданному запросу.
     * 
     * Если Select без join'ов, генерируется плоский DELETE, 
     * в противном случае используется конструкция IN (SELECT ...)
     * на первичный ключ.
     * 
     * Набор выбиаемых столбцов игнорируется.
     * 
     * @param s объект Select.
     * @throws SQLException
     */
    public final void delete (Select s) throws SQLException {
        d0 (toDeleteQP (s));
    }
        
    /**
     * Низкоуровневая функция, которую не надо бы вызывать напрямую. Но мало ли...
     * @param b Комплект из SQL с параметрами
     * @param sub Обработчик результата
     * @throws SQLException
     */
    public final void execute (SQLBuilder b, JDBCConsumer<PreparedStatement> sub) throws SQLException {
        
        long tsStart = System.currentTimeMillis ();
                
        try (PreparedStatement st = cn.prepareStatement (b.getSQL ())) {
            
//            long tsPrepared = System.currentTimeMillis ();

            b.setParams (st, this);
            
//            long tsSet = System.currentTimeMillis ();

            logger.log (Level.INFO, b.toString ());
            
            sub.accept (st);
            
            long tsDone = System.currentTimeMillis ();
            
            logger.log (Level.INFO, 
                "done, "   +                (tsDone     - tsStart) + " ms"
//                " ms: prepared in "   +       (tsPrepared - tsStart) + 
//                " ms, params set in " +       (tsSet      - tsPrepared) + 
//                " ms, executed/fetched in " + (tsDone     - tsSet)
            );

        }
        catch (SQLException ex) {

            logger.log (Level.SEVERE, ex.getMessage () + " for " + b.getSQL ());
            
            throw ex;

        }
        
    }
    
    /**
     * Вызов DML/DDL-запроса с заданным списком параметров.
     * Если этот список пуст, исполняется createStatement, иначе -- prepareStatement
     * @param b Не-SELECT запрос с параметрами
     * @throws SQLException
     */
    public final void d0 (SQLBuilder b) throws SQLException {

        if (b.isToPrepare ()) {
            execute (b, st -> {b.execute (st);});        
        }
        else {
            
            long tsStart = System.currentTimeMillis ();
            
            logger.log (Level.INFO, b.toString ());

            try (Statement st = cn.createStatement ()) {
                st.execute (b.getSQL ());
            }
            
            long tsDone = System.currentTimeMillis ();
            
            logger.log (Level.INFO, "Done in " + (tsDone - tsStart) + " ms");
            
        }

    }

    /**
     * Обработка первой (и только первой) записи выборки по заданному Select.
     * 
     * Если есть ещё записи, они игнорируются.
     * 
     * Если выборка пуста, обработчик не вызывается -- и всё (ошибки не возникает).
     * 
     * @param s запрос
     * @param sub обработчик 1-й записи
     * @throws SQLException
     */
    public final void forFirst (Select s, JDBCConsumer<ResultSet> sub) throws SQLException {
        DB.this.forFirst (toQP (s), sub);        
    }
    
    /**
     * Обработка первой (и только первой) записи выборки по заданному Select.
     * 
     * Если есть ещё записи, они игнорируются.
     * 
     * Если выборка пуста, обработчик не вызывается -- и всё (ошибки не возникает).
     * 
     * @param qp SQL + параметры
     * @param sub обработчик 1-й записи
     * @throws SQLException
     */
    public final void forFirst (QP qp, JDBCConsumer<ResultSet> sub) throws SQLException {        
        execute (qp, st -> {try (ResultSet rs = st.executeQuery ()) {
            if (rs.next ()) sub.accept (rs);
        }});        
    }
    
    /**
     * Прогон обработчика по всей выборке.
     * @param qp SQL + параметры
     * @param sub обработчик
     * @throws SQLException
     */
    public final void forEach (QP qp, JDBCConsumer<ResultSet> sub) throws SQLException {        
        execute (qp, st -> {try (ResultSet rs = st.executeQuery ()) {
            while (rs.next ()) sub.accept (rs);
        }});        
    }
    
    /**
     * Прогон обработчика по всей выборке.
     * @param s Запрос
     * @param sub обработчик
     * @throws SQLException
     */
    public final void forEach (Select s, JDBCConsumer<ResultSet> sub) throws SQLException {
        forEach (toQP (s), sub);
    }
                
    private final TableRecordSQLBuilder createInsertSQLBuilder (Table t, Map<String, Object> r) throws SQLException {
        TableRecordSQLBuilder b = new TableRecordSQLBuilder (t, r);
        genInsertSql (b);
        return b;
    }    
    
    /**
     * Добавление в таблицу отдельной записи с возвратом первичного ключа
     * @param c Класс описания таблицы
     * @param r Запись, которую надо добавить
     * @return Первичный ключ добавленной
     * @throws SQLException
     */
    public final Object insertId (Class c, Map<String, Object> r) throws SQLException {                
        return insertId (model.get (c), r);        
    }

    /**
     * Добавление в таблицу отдельной записи с возвратом первичного ключа
     * @param t Описание таблицы
     * @param r Запись, которую надо добавить
     * @return Первичный ключ добавленной
     * @throws SQLException
     */
    public final Object insertId (Table t, Map<String, Object> r) throws SQLException {
                
        String [] pkColNames = t.getPkColNames ();
        
        if (pkColNames.length != 1) throw new IllegalArgumentException ("Vector PKs are not supported");
                
        r.remove (pkColNames [0]);
                
        TableRecordSQLBuilder b = createInsertSQLBuilder (t, r);
        
        try (PreparedStatement st = cn.prepareStatement (b.getSQL (), pkColNames)) {
            
            b.setParams (st, this);
            
            logger.log (Level.INFO, b.toString ());
            
            st.executeUpdate ();            
            
            try (ResultSet rs = st.getGeneratedKeys ()) {
                
                rs.next ();

                return getValue (rs, 1);
                
            }
            
        }        
                
    }

    /**
     * Добавление в таблицу отдельной записи БЕЗ возврата первичного ключа 
     * (для скорости)
     * @param c Класс описания таблицы
     * @param r Запись, которую надо добавить
     * @throws SQLException
     */
    public final void insert (Class c, Map<String, Object> r) throws SQLException {

        insert (model.get (c), r);
      
    }

    /**
     * Добавление в таблицу отдельной записи БЕЗ возврата первичного ключа 
     * (для скорости)
     * @param t Описание таблицы
     * @param r Запись, которую надо добавить
     * @throws SQLException
     */
    public final void insert (Table t, Map<String, Object> r) throws SQLException {

        d0 (createInsertSQLBuilder (t, r));
      
    }

    /**
     * Получение по первичному ключу нужной записи в виде хэша.
     * @param clazz Класс описания таблицы
     * @param id Первичный ключ записи
     * @return Запись в виде хэша, либо null, если она не найдена
     * @throws SQLException
     */
    public final Map<String, Object> getMap (Class clazz, Object id) throws SQLException {
        return getMap (model.get (clazz), id);
    }

    /**
     * Получение по первичному ключу нужной записи в виде хэша.
     * @param t Описание таблицы
     * @param id Первичный ключ записи
     * @return Запись в виде хэша, либо null, если она не найдена
     * @throws SQLException
     */
    public final Map<String, Object> getMap (Table t, Object id) throws SQLException {
        return getMap (new Get (t, id, "*"));
    }

    /**
     * Получение (максимум) первой записи в виде хэша.
     * @param s Запрос
     * @return Первая запись выборки в виде хэша, либо null, если выборка пуста
     * @throws SQLException
     */
    public final Map<String, Object> getMap (Select s) throws SQLException {
        return getMap (toQP (s));
    }
    
    /**
     * Получение (максимум) первой записи в виде хэша.
     * @param qp SQL + параметры
     * @return Первая запись выборки в виде хэша, либо null, если выборка пуста
     * @throws SQLException
     */
    public final Map<String, Object> getMap (QP qp) throws SQLException {
        
        Object [] r = new Object [] {null};
        
        DB.this.forFirst (qp, rs -> {r [0] = HASH (rs);});
        
        return (Map<String, Object>) r [0];
        
    }    

    /**
     * Строковое значение заданного поля в нужной записи.
     * 
     * Если запись не найдена, возвращает null.
     * 
     * В противном случае НЕ возвращает null, даже если из БД извлечено
     * значение NULL (оно заменяется на "").
     * 
     * @param clazz Класс описания таблицы
     * @param id Значение первичного ключа
     * @param field Имя поля
     * @return Результат
     * @throws SQLException
     */    
    public final String getString (Class clazz, Object id, String field) throws SQLException {
        return getString (model.get (clazz), id, field);
    }
    
    /**
     * Строковое значение заданного поля в нужной записи.
     * 
     * Если запись не найдена, возвращает null.
     * 
     * В противном случае НЕ возвращает null, даже если из БД извлечено
     * значение NULL (оно заменяется на "").
     * 
     * @param table Описание таблицы
     * @param id Значение первичного ключа
     * @param field Имя поля
     * @return Результат
     * @throws SQLException
     */    
    public final String getString (Table table, Object id, String field) throws SQLException {                
        return getString (toQP (new Get (table, id, field)));        
    }
    
    /**
     * Строковое значение первого поля в первой записи выборки.
     * 
     * Если выборка пуста, возвращает null.
     * 
     * В противном случае НЕ возвращает null, даже если из БД извлечено
     * значение NULL (оно заменяется на "").
     * 
     * @param s Запрос
     * @return Результат
     * @throws SQLException
     */    
    public final String getString (Select s) throws SQLException {
        return getString (toQP (s));
    }
    
    /**
     * Строковое значение первого поля в первой записи выборки.
     * 
     * Если выборка пуста, возвращает null.
     * 
     * В противном случае НЕ возвращает null, даже если из БД извлечено
     * значение NULL (оно заменяется на "").
     * 
     * @param qp SQL + параметры
     * @return Результат
     * @throws SQLException
     */
    public final String getString (QP qp) throws SQLException {
        
        String [] ss = new String [] {null};
        
        DB.this.forFirst (qp, rs -> {
            ss [0] = rs.getString (1);
            if (rs.wasNull ()) ss [0] = "";
        });
        
        return ss [0];
        
    } 
    
    /**
     * Потоковое значение заданного поля в нужной записи.
     * 
     * Это поле должно иметь JDBC-тип BLOB.
     * 
     * Если там записано бинарное содержимое, оно перекачивается в заданный 
     * поток порциями не более 8 Кб. 
     * 
     * Если запись не найдена или значение поля пусто — с потоком ничего не происходит.
     * 
     * @param clazz Описание таблицы
     * @param id Значение первичного ключа
     * @param field Имя поля
     * @param out поток, в который надо перекачать BLOB bp 1-го поля выборки 1-й записи выборки.
     * @throws SQLException
     */
    public final void getStream (Class clazz, Object id, String field, OutputStream out) throws SQLException {                
        getStream (model.get (clazz), id, field, out);
    }
    
    /**
     * Потоковое значение заданного поля в нужной записи.
     * 
     * Это поле должно иметь JDBC-тип BLOB.
     * 
     * Если там записано бинарное содержимое, оно перекачивается в заданный 
     * поток порциями не более 8 Кб. 
     * 
     * Если запись не найдена или значение поля пусто — с потоком ничего не происходит.
     * 
     * @param table Таблица
     * @param id Значение первичного ключа
     * @param field Имя поля
     * @param out поток, в который надо перекачать BLOB bp 1-го поля выборки 1-й записи выборки.
     * @throws SQLException
     */
    public final void getStream (Table table, Object id, String field, OutputStream out) throws SQLException {                
        getStream (toQP (new Get (table, id, field)), out);        
    }
    
    /**
     * Потоковое значение первого поля в первой записи выборки.
     * 
     * Это поле должно иметь JDBC-тип BLOB.
     * 
     * Если там записано бинарное содержимое, оно перекачивается в заданный 
     * поток порциями не более 8 Кб. 
     * 
     * Если запись не найдена или значение поля пусто — с потоком ничего не происходит.
     * 
     * @param select запрос
     * @param out поток, в который надо перекачать BLOB bp 1-го поля выборки 1-й записи выборки.
     * @throws SQLException
     */
    public final void getStream (Select select, OutputStream out) throws SQLException {
        getStream (toQP (select), out);
    }    
        
    /**
     * Потоковое значение первого поля в первой записи выборки.
     * 
     * Это поле должно иметь JDBC-тип BLOB.
     * 
     * Если там записано бинарное содержимое, оно перекачивается в заданный 
     * поток порциями не более 8 Кб. 
     * 
     * Если запись не найдена или значение поля пусто — с потоком ничего не происходит.
     * 
     * @param qp SQL + параметры
     * @param out поток, в который надо перекачать BLOB bp 1-го поля выборки 1-й записи выборки.
     * @throws SQLException
     */
    public final void getStream (QP qp, OutputStream out) throws SQLException {
        
        Blob [] bb = new Blob [] {null};
        
        DB.this.forFirst (qp, rs -> {
            
            bb [0] = rs.getBlob (1);
            if (rs.wasNull ()) return;
            
            byte [] buffer = new byte [8 * 1024];
            int len;
            
            try (InputStream in = bb [0].getBinaryStream ()) {                        
                while ((len = in.read (buffer)) > 0) out.write(buffer, 0, len);                        
            }
            catch (IOException ex) {
                throw new IllegalStateException (ex);
            }            
                        
        });
                
    }    

    /**
     * Извлечение целого значения из заданного поля нужной записи.
     * @param clazz Класс описания таблицы
     * @param id Значение первичного ключа интеерсующей записи
     * @param field Имя поля (целого типа)
     * @return Значение поля или null, если запись не найдена.
     * @throws SQLException
     */
    public final Integer getInteger (Class clazz, Object id, String field) throws SQLException {
        return getInteger (model.get (clazz), id, field);
    }

    /**
     * Извлечение целого значения из заданного поля нужной записи.
     * @param table Описание таблицы
     * @param id Значение первичного ключа интеерсующей записи
     * @param field Имя поля (целого типа)
     * @return Значение поля или null, если запись не найдена.
     * @throws SQLException
     */
    public final Integer getInteger (Table table, Object id, String field) throws SQLException {                
        return getInteger (toQP (new Get (table, id, field)));        
    }

    /**
     * Извлечение целого значения из первого попавшегося поля 
     * первой записи заданного запроса.
     * @param qp Запрос + параметры
     * @return Значение 1-го поля в SELECT для 1-й извлечённой записи 
     * или null, если выборка пуста.
     * @throws SQLException
     */
    public final Integer getInteger (QP qp) throws SQLException {
        
        Integer [] ii = new Integer [] {null};
        
        forFirst (qp, rs -> {ii [0] = rs.getInt (1);});
        
        return ii [0];
        
    }
    
    /**
     * Полное число записей в выборке, без учёта limit.
     * 
     * По ходу исполнения для того же Select генерируется специальный SQL, откуда,
     * в частности, убраны все LEFT JOIN'ы без параметров.
     * 
     * Этот метод в основном предназначен для вызова не напрямую, а через addJsonArrayCnt.
     * 
     * @param s Запрос
     * @return COUNT(*) без учёта limit
     * @throws SQLException
     */
    public final int getCnt (Select s) throws SQLException {
        return getInteger (toCntQP (s));
    }
    
    /**
     * Выдача (максимум) первой записи выборки в виде JSON-объекта.
     * @param qp SQL + параметры
     * @return Запись как JSON-объект, либо null, если запрос не имеет результатов.
     * @throws SQLException
     */
    public final JsonObject getJsonObject (QP qp) throws SQLException {
        
        JsonObject [] oo = new JsonObject [] {null};
        
        forFirst (qp, rs -> {oo [0] = getJsonObject (rs);});
        
        return oo [0];
        
    }    
    
    /**
     * Получение по первичному ключу нужной записи в виде JSON-объекта.
     * @param t Класс описания таблицы
     * @param id Первичный ключ записи
     * @return Запись в виде хэша, либо null, если она не найдена
     * @throws SQLException
     */
    public final JsonObject getJsonObject (Class t, Object id) throws SQLException {
        return getJsonObject (model.get (t), id);
    }

    /**
     * Получение по первичному ключу нужной записи в виде JSON-объекта.
     * @param t Описание таблицы
     * @param id Первичный ключ записи
     * @return Запись в виде хэша, либо null, если она не найдена
     * @throws SQLException
     */
    public final JsonObject getJsonObject (Table t, Object id) throws SQLException {
        return getJsonObject (new Get (t, id, "*"));
    }
    
    /**
     * Выдача (максимум) первой записи выборки в виде JSON-объекта.
     * @param s запрос
     * @return Запись как JSON-объект, либо null, если запрос не имеет результатов.
     * @throws SQLException
     */
    public final JsonObject getJsonObject (Select s) throws SQLException {
        return getJsonObject (toQP (s));
    }

    /**
     * Выдача очередной записи выборки в виде JSON-объекта.
     * @param rs курсор, для которого был успешно вызван next ()
     * @return Запись как JSON-объект
     * @throws SQLException
     */
    public final JsonObject getJsonObject (ResultSet rs) throws SQLException {
        return getJsonObjectBuilder (rs, rs.getMetaData ()).build ();
    }
    
    /**
     * Выдача очередной записи выборки в виде открытого для записи JSON-объекта.
     * @param rs курсор, для которого был успешно вызван next ()
     * @return Запись как открытый JSON-объект
     * @throws SQLException
     */
    public final JsonObjectBuilder getJsonObjectBuilder (ResultSet rs) throws SQLException {
        return getJsonObjectBuilder (rs, rs.getMetaData ());
    }

    /**
     * Выдача очередной записи выборки в виде открытого для записи JSON-объекта (оптимизированный вариант).
     * @param rs курсор, для которого был успешно вызван next ()
     * @param md предварительно закэшированные метаданные.
     * @return Запись как JSON-объект
     * @throws SQLException
     */
    public final JsonObjectBuilder getJsonObjectBuilder (ResultSet rs, ResultSetMetaData md) throws SQLException {
        
        JsonObjectBuilder jb = Json.createObjectBuilder ();
                
        for (int n = 1; n <= md.getColumnCount (); n ++) {
            
            if (md.getColumnType (n) == java.sql.Types.BLOB) continue;

            Object v = getValue (rs, n);

            if (v == null) continue;

            final String columnName = md.getColumnName (n);

            if (v instanceof Integer) {
                jb.add (columnName, ((Integer) v).intValue ());
            }
            else if (v instanceof Long) {
                jb.add (columnName, ((Long) v).longValue ());
            }
            else {
                jb.add (columnName, v.toString ());
            }
            
        }
        
        return jb;
        
    }

    /**
     * Извлечение содержимого выборки в виде JSON-массива.
     * @param rs Результат запроса
     * @return JSON-массив записей
     * @throws SQLException
     */
    public final JsonArray getJsonArray (ResultSet rs) throws SQLException {
        
        JsonArrayBuilder ab = Json.createArrayBuilder ();
        
        ResultSetMetaData md = rs.getMetaData ();
        
        while (rs.next ()) ab.add (getJsonObjectBuilder (rs, md));
        
        return ab.build ();
        
    }
    
    /**
     * Извлечение выборки по запросу в виде JSON-массива.
     * @param qp SQL + параметры
     * @return JSON-массив записей
     * @throws SQLException
     */
    public final JsonArray getJsonArray (QP qp) throws SQLException {
        
        JsonArrayBuilder ab = Json.createArrayBuilder ();
        
        forEach (qp, rs -> {ab.add (getJsonObject (rs));});
        
        return ab.build ();
        
    }
    
    /**
     * Извлечение выборки по запросу в виде JSON-массива.
     * @param s Запрос
     * @return JSON-массив записей
     * @throws SQLException
     */
    public final JsonArray getJsonArray (Select s) throws SQLException {
        
        return getJsonArray (toQP (s));
        
    }
    
    /**
     * Формирование JSON-массивов с выборками по заданным запросам.
     * 
     * На каждый запрос в заданный объект добавляется элемент со списком записей.
     * 
     * Имена полей соответствуют псевдонимам главных таблиц в запросах. 
     * Если требуется добавить несколько выборок из одной таблицы, в запросах 
     * следует прописать разные псевдонимы.
     * 
     * @param jb куда добавлять выборки
     * @param ss запросы
     * @return jb
     * @throws SQLException
     */
    public final JsonObjectBuilder addJsonArrays (JsonObjectBuilder jb, Select... ss) throws SQLException {
                
        for (Select s: ss) jb.add (s.getTableAlias (), getJsonArray (s));
        
        return jb;
        
    }
    
    /**
     * Формирование JSON для таблицы с листанием. В заданный объект добавляются 2 поля:
     * 1) с названием, как псевдоним главной таблицы запроса и значением - списком выбранных записей;
     * 2) с названием "cnt" и значением COUNT(*) без LIMIT.
     * @param jb куда добавить поля
     * @param s запрос с установленным limit
     * @return jb
     * @throws SQLException
     */
    public final JsonObjectBuilder addJsonArrayCnt (JsonObjectBuilder jb, Select s) throws SQLException {
                
        jb.add (s.getTableAlias (), getJsonArray (s));
        jb.add ("cnt", getCnt (s));
        
        return jb;
        
    }     
    
    /**
     * Извлечение выборки по запросу в виде индекса: ключ-запись
     * @param rs выборка
     * @param key имя ключевого поля
     * @return Map, где значениям ключевого поля соответствуют записи, полученные как HASH
     * @throws SQLException
     */
    
    public final Map <Object, Map <String, Object>> getIdx (ResultSet rs, String key) throws SQLException {
        
        Map <Object, Map <String, Object>> result = new HashMap ();
        
        while (rs.next ()) {
            Map<String, Object> r = HASH (rs);
            result.put (r.get (key), r);
        }
        
        return result;
        
    }
    
    /**
     * Извлечение выборки по запросу в виде индекса: ключ-запись
     * @param qp запрос
     * @param key имя ключевого поля
     * @return Map, где значениям ключевого поля соответствуют записи, полученные как HASH
     * @throws SQLException
     */
    
    public final Map <Object, Map <String, Object>> getIdx (QP qp, String key) throws SQLException {
        
        Map <Object, Map <String, Object>> result = new HashMap ();
        
        forEach (qp, rs -> {
            Map<String, Object> r = HASH (rs);
            result.put (r.get (key), r);
        });
                
        return result;
        
    }
        
    /**
     * Извлечение выборки по запросу в виде индекса: ключ-запись
     * @param s запрос
     * @param key имя ключевого поля (если null — используется имя 1-го поля PK главной таблицы запроса)
     * @return Map, где значениям ключевого поля соответствуют записи, полученные как HASH
     * @throws SQLException
     */
    
    public final Map <Object, Map <String, Object>> getIdx (Select s, String key) throws SQLException {
        
        if (key == null) key = s.getTable ().getPk ().get (0).getName ();
        
        return getIdx (toQP (s), key);
        
    }
    
    /**
     * Извлечение выборки по запросу в виде индекса: ключ-запись
     * @param s запрос
     * @param key имя ключевого поля (если null — используется имя 1-го поля PK главной таблицы запроса)
     * @return Map, где значениям ключевого поля соответствуют записи, полученные как HASH
     * @throws SQLException
     */
    
    public final Map <Object, Map <String, Object>> getIdx (Select s) throws SQLException {
        
        return getIdx (s, null);
        
    }

    public abstract QP toQP (Select s);
    protected abstract QP toCntQP (Select s);
    protected abstract QP toDeleteQP (Select s);
    
    /**
     * Извлечение значения заданного поля выборки в виде java-объекта.
     * @param rs Курсор, для которого успешно вызван next ()
     * @param n Номер нужного поля (начиная с 1, как положено в JDBC)
     * @return null для значения NULL, иначе -- String, Integer, Long, BigDecimal, java.sql.Timestamp или UUID в зависимости от типа поля.
     * @throws SQLException
     */
    public abstract Object getValue (ResultSet rs, int n) throws SQLException;
    
    /**
     * Установка внутренних ссылок в модели данных. 
     * 
     * Этот метод должен быть вызван по ходу развёртывания приложения
     * после загрузки описаний всех таблиц, но до updateSchema ().
     */
    public abstract void adjustModel ();

    /**
     * Обеспечить наличие в БД таблицы с заданными описаниями
     * @param t Новые (обновлённые) описания таблиц
     * @throws SQLException 
     */
    public abstract void updateSchema (Table... t) throws SQLException; 
    
    /**
     * Приведение физической схемы данных в сответствие логической схеме,
     * описанной Model: создание таблиц, добавление столбцов и т. п.
     * 
     * Этот метод должен вызываться по ходу развёртывания приложения 
     * после adjustModel (), но до запуска.
     * 
     * @throws SQLException
     */
    public abstract void updateSchema () throws SQLException;

    protected abstract String toVarbinary (Object v);
    protected abstract void genUpsertSql (TableSQLBuilder b, Roster<PhysicalCol> keyCols);
    protected abstract void genInsertSql (TableSQLBuilder b);
    protected abstract void genUpdateSql (TableSQLBuilder b);
    protected abstract SQLBuilder genUpsertSql (Table t, Table records, String... key);
    protected abstract SQLBuilder genTruncateSql (Table t);
    
    /**
     * Пародия на java.sql.ResultSet, реализующая некоторые методы, 
     * которых там не хватает.
     */
    public static class ResultGet {

        ResultSet rs;

        public ResultGet (ResultSet rs) {
            this.rs = rs;
        }
        
        /**
         * Извлечение логического значения при условии того, что 
         * "истина" представлена числом 1.
         * @param name Имя поля
         * @return null для NULL, true для 1, иначе false.
         * @throws SQLException
         */
        public final Boolean getBoolean (String name) throws SQLException {
            int i = rs.getInt (name);
            return rs.wasNull () ? null : (i == 1);
        }
        
        /**
         * Извлечение короткого целого в виде java-объекта (без подмены NULL на 0, как в JDBC)
         * @param name Имя поля
         * @return null для NULL, иначе - значение поля.
         * @throws SQLException
         */
        public final Short getShort (String name) throws SQLException {
            short i = rs.getShort (name);
            return rs.wasNull () ? null : i;
        }
        
        /**
         * Доступ к стандартному ResultSet
         * @return то, обёрткой чего является данный объект.
         */
        public ResultSet getRs () {
            return rs;
        }
        
        /**
         * UUID в виде стандартной строки. Этот метод имеет смысл для Oracle,
         * где UUID хранится в виде RAW(16) и как строка выдаётся в верхнем регистре 
         * и без разделителей.
         * @param name имя поля
         * @return Строка в формате UUID
         * @throws SQLException
         */
        public final String getUUIDString (String name) throws SQLException {
            byte [] b = rs.getBytes (name);
            if (rs.wasNull ()) return null;
            return to.UUID (b).toString ();
        }

        /**
         * Дата/время в формате, пригодном для подстановки в XML.
         * @param name имя поля
         * @return значение типа, используемого JAXB.
         * @throws SQLException
         */
        public final XMLGregorianCalendar getXMLGregorianCalendar (String name) throws SQLException {
            java.sql.Timestamp ts = rs.getTimestamp (name);
            if (rs.wasNull ()) return null;
            return to.XMLGregorianCalendar (ts);
        }
                
    }
    
    /**
     * Базовый класс буферов для пакетной обработки записей.
     * 
     * Основная мысль: приложение создаёт такой буфер в try-with
     * и далее добавляет туда записи (в цикле, при чтении потока и т. п.).
     * 
     * С точки зрения приложения, записи обрабатываются по отдельности.
     * 
     * Буфер же отвечает за то, чтобы:
     * 1) требуемая операция выполнялась не на каждую запись, а пакетом
     * 2) чтобы пакеты не превышали заданного размера
     * 3) и чтобы на выходе блока последний пакет был обработан.
     */
    public abstract class RecordBuffer implements AutoCloseable {
        
        Table t;
        int size;
        List<Map <String, Object>> records;
        long total = 0L;
        Consumer<Long> onFlush = null;

        RecordBuffer (Table t, int size) {
            if (size <= 0) throw new IllegalArgumentException ("Illegal size value: " + size);
            this.t = t;
            this.size = size;
            records = new ArrayList<> (size);
        }

        public void setOnFlush (Consumer<Long> onFlush) {
            this.onFlush = onFlush;
        }
        
        abstract void write () throws SQLException;
        
        final void flush () throws SQLException {
            if (records.isEmpty ()) return;
            write ();
            if (onFlush != null) onFlush.accept (total);
            records.clear ();
        }

        public List<Map<String, Object>> getRecords () {
            return records;
        }

        @Override
        public void close () throws Exception {
            flush ();
        }
        
        /**
         * Добавление записи в буфер. Если перед этим в буфере уже (size-1) 
         * запись, то запускается пакетная операция.
         * @param r Запись
         * @throws SQLException
         */
        public void add (Map <String, Object> r) throws SQLException {
            records.add (r);
            total ++;
            if (records.size () < size) return;
            flush ();
        }

        @Override
        public String toString () {
            return records.toString ();
        }
        
    }

    /**
     * Буфер для пакетированного обновления множества записей в таблице.
     * 
     * Генерирует массовый UPDATE:
     * 1) при вызове метода add () каждые size записей, а также 
     * 2) при вызове close ().
     * 
     * Предназначен для использования в конструкции try-with, гарантирующей
     * сброс буфера по окончании процесса.
     */
    public class UpdateBuffer extends RecordBuffer {
        
        public UpdateBuffer (Table t, int size) {
            super (t, size);
        }
        
        public UpdateBuffer (String t, int size) {
            this (model.get (t), size);
        }
        
        public UpdateBuffer (Class t, int size) {
            this (model.get (t), size);
        }        

        @Override
        void write () throws SQLException {
            update (t, records);
        }

    }

    /**
     * Буфер для пакетированной загрузки в таблицу множества записей,
     * генерируемых в длинном цикле.
     * 
     * Генерирует массовый INSERT:
     * 1) при вызове метода add () каждые size записей, а также 
     * 2) при вызове close ().
     * 
     * Предназначен для использования в конструкции try-with, гарантирующей
     * сброс буфера по окончании процесса.
     */
    public class InsertBuffer extends RecordBuffer {
        
        public InsertBuffer (Table t, int size) {
            super (t, size);
        }
        
        public InsertBuffer (String t, int size) {
            this (model.get (t), size);
        }
        
        public InsertBuffer (Class t, int size) {
            this (model.get (t), size);
        }        

        @Override
        void write () throws SQLException {
            insert (t, records);
        }

    }
    
    /**
     * Буфер для пакетированной синхронизации множества записей,
     * генерируемых в длинном цикле.
     * 
     * Генерирует массовый upsert ():
     * 1) при вызове метода add () каждые size записей, а также 
     * 2) при вызове close ().
     * 
     * Предназначен для использования в конструкции try-with, гарантирующей
     * сброс буфера по окончании процесса.
     */
    public class UpsertBuffer extends RecordBuffer {
        
        String [] key;

        public UpsertBuffer (Table t, int size, String... key) {
            super (t, size);
            this.key = key;
        }
        
        public UpsertBuffer (String t, int size, String... key) {
            this (model.get (t), size, key);
        }
        
        public UpsertBuffer (Class t, int size, String... key) {
            this (model.get (t), size, key);
        }
                
        @Override
        void write () throws SQLException {
            upsert (t, records, key);
        }
        
    }
    
    /**
     * Буфер для пакетированной синхронизации множества записей,
     * генерируемых в длинном цикле, с накоплением в промежуточной таблице.
     * 
     * Генерирует массовый INSERT в буферную таблицу:
     * 1) при вызове метода add () каждые size записей, а также 
     * 2) при вызове close ().
     * 
     * Переливает данные из буферной таблицы в целевую и очищает буфер:
     * 1) когда накапливается tbSize записей;
     * 2) при вызове close ().
     * 
     * Предназначен для использования в конструкции try-with, гарантирующей
     * сброс буфера по окончании процесса.
     * 
     * Буферная таблица tb используется монопольно: в частности, она полностью
     * очищается в начале и по завершении процесса. Параллельное использование
     * 2 и более TableUpsertBuffer с одной tb недопустимо.
     * 
     */
    public class TableUpsertBuffer extends RecordBuffer {
        
        int tbSize;
        Table tb;
        String [] key;
        int cnt;

        public TableUpsertBuffer (Table t, int size, Table tb, int tbSize, String... key) throws SQLException {
            super (t, size);
            if (tbSize < size) throw new IllegalArgumentException ("tbSize (" + tbSize +  ") cannot be less than size: " + size);
            this.tbSize = tbSize;
            this.tb = tb;
            this.key = key;
            clearBufferTable ();
        }

        /**
         * Конструктор
         * @param t Описание таблицы-получателя данных
         * @param size Максимальное число записей, хранимое в памяти (до отправки в tb)
         * @param tb Описание таблицы-буфера данных 
         * @param tbSize Максимальное число записей в tb
         * @param key Ключ синхронизации. Если пусто, используется первичный ключ tb
         */
        public TableUpsertBuffer (Class t, int size, Class tb, int tbSize, String... key) throws SQLException {
            this (model.get (t), size, model.get (tb), tbSize, key);
        }

        private void clearBufferTable () throws SQLException {
            truncate (tb);
            cnt = 0;
        }
        
        private void flushBufferTable () throws SQLException {
            upsert (t, tb, key);
            clearBufferTable ();
        } 
        
        @Override
        void write () throws SQLException {
            insert (tb, records);
            cnt += records.size ();
            if (cnt >= tbSize) flushBufferTable ();
        }

        @Override
        public void close () throws Exception {
            super.close (); 
            flushBufferTable ();
        }
        
    }
    
    public abstract QP toLimitedQP (QP qp, int offset, Integer limit);
    public abstract PhysicalCol toPhysical (Col col);
    public abstract void adjustTable (Table t);

}