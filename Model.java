package ru.eludia.base;

import ru.eludia.base.model.abs.AbstractModel;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.zip.ZipFile;
import javax.sql.DataSource;
import ru.eludia.base.model.Col;
import ru.eludia.base.model.Key;
import ru.eludia.base.model.Table;
import static ru.eludia.base.DB.DB;
import ru.eludia.base.db.sql.gen.Get;
import ru.eludia.base.db.sql.gen.Select;

/**
 * Описание модели данных приложения: справочник описаний таблиц.
 * 
 * Также служит фабрикой DB: соединений с БД.
 * 
 * В норме экземпляр этого класса должен быть один на всё приложение 
 * и упоминаться во всех классах, работающих с БД, как статическое поле.
 */
public class Model extends AbstractModel<Col, Key, Table> {
    
    private static final String DEFAULT_ORDER_FIELD = "label";
    
    DataSource ds;
    
    /**
     * Выдача эксемпляра DB: основного класса для работы с БД.
     * @return нетонкая обёртка над java.sql.Connection
     * @throws SQLException 
     */
    public final DB getDb () throws SQLException {
        return DB (getConnection (), this);
    }
    
    /**
     * Этот метод лучше не вызывать. Есть же getDb ().
     * @return экземпляр java.sql.Connection, выданный источником данных этой модели.
     * @throws SQLException 
     */
    public final Connection getConnection () throws SQLException {
        return ds.getConnection ();
    }
    
    /**
     * Приведение физической схемы данных в сответствие логической схеме,
     * описанной Model: создание таблиц, добавление столбцов и т. п.
     * 
     * @throws SQLException 
     */
    public final void update () throws SQLException {        
        try (DB db = getDb ()) {                
            db.updateSchema ();
        }       
    }
    
    /**
     * Конструктор
     * @param dsName JNDI-имя источника данных
     * @param packageNames имена java-пакетов с описаниями таблиц
     */
    public Model (DataSource ds, String... packageNames) throws IOException, SQLException {
        
        this.ds = ds;
                
        for (String i: packageNames) addPackage (i);
                        
        try (DB db = getDb ()) {                
            db.adjustModel  ();
        }            
                       
    }
    
            
    private void addPackageFromZipUrl (String p, URL url) throws Exception {

logger.info ("url = " + url);
        
        String s = url.toString ().substring (4);
        
        StringTokenizer st = new StringTokenizer (s, "!");
        String f = st.nextToken ();
        String l = st.nextToken ().substring (1);
        
        int len = s.endsWith ("/") ? l.length () : l.length () + 1;
        
        ZipFile zf = new ZipFile (f);

        Collections.list (zf.entries ()).forEach (i -> {
            
            String n = i.getName ();
            
            if (n.length () > len && n.startsWith (l)) {
                
                final String ss = n.substring (len);
                
                if (!ss.contains ("/")) {

                    try {
                        addByName (p, ss);
                    }
                    catch (Exception ex) {
                        logger.log (Level.SEVERE, null, ex);
                    }
                
                }
            
            }
            
        });
        
        zf.close ();
        
    }
    
    private void addPackageFromUrl (String p, URL url) throws Exception {
        
        switch (url.getProtocol ()) {
            case "jar":
            case "zip":
                addPackageFromZipUrl (p, url);        
                break;
            default:
                addPackageFromFileUrl (p, url);        
        }
        
    }

    private void addByName (String p, String name) throws Exception {
        
        if (name.endsWith (".class")) name = name.substring (0, name.length () - 6);
        
        Class c = Class.forName (p + '.' + name);
        
        logger.info ("loading = " + c + "...");
        
        if (Modifier.isAbstract (c.getModifiers ())) {            
            logger.info (" ...bypassing as abstract.");
            return;
        }
        
        if (!Table.class.isAssignableFrom (c)) {
            logger.info (" ...not a Table subclass.");
            return;
        }
            
        add ((Table) c.newInstance ());
        logger.info (" ...ok.");

    }

    private void addPackageFromFileUrl (String p, URL url) throws Exception {
        
        Files.walk (Paths.get (url.toURI ()), 1).iterator ().forEachRemaining (path -> {             
            try {
                String fn = path.getFileName ().toString ();
                if (fn != null && fn.endsWith (".class")) addByName (p, fn);
            }
            catch (Exception ex) {
                logger.log (Level.SEVERE, null, ex);
            }
        
        });
    
    }
    
    public final void addPackage (String p) throws IOException {
        
        logger.log (Level.INFO, "Registering table definitions from {0}...", new Object[]{p});

        String path = p.replace ('.', '/');
        
        List<URL> urlList = new ArrayList<URL> ();
        
        Collections.list (Thread.currentThread ().getContextClassLoader ().getResources (path)).forEach (url -> {
                
            try {
                if (!urlList.contains(url)) {
                    addPackageFromUrl (p, url);
                    urlList.add(url);
                } else {
                    logger.log (Level.WARNING, "Repeatable URLs found! Skipping...");
                }
                    
            }
            catch (Exception ex) {
                logger.log (Level.SEVERE, null, ex);
            }

        });
                            
        logger.log (Level.INFO, "Done registering table definitions from {0}.", new Object[]{p});

    }
    
    /**
     * Вычислить описание таблицы по имени
     * @param name имя таблицы, класса или иной псевдоним
     * @return само описание
     */
    public final Table t (String name) {
        if (!tables.containsKey (name)) throw new IllegalArgumentException ("Table not found by name: " + name);
        return tables.get (name);
    }
    
    /**
     * Вычислить описание таблицы по соответствующему классу.
     * @param c класс описания
     * @return само описание
     */
    public final Table t (Class c) {
        return t (c.getName ());
    }
    
    /**
     * Вычислить физическое имя таблицы по описывающему её классу.
     * @param c Описание таблицы
     * @return Имя, пригодное для подстановки в SQL
     */
    public final String getName (Class c) {
        return t (c).getName ();
    }

    /**
     * Генератор SELECT
     * @param t имя 1-й таблицы в разделе FROM 
     * @param c "AS псевдоним" + поля
     * @return 
     */
    public final Select select (String t, String... c) {
        return new Select (t (t), c);
    }
    
    /**
     * Генератор SELECT
     * @param t описание 1-й таблицы в разделе FROM 
     * @param c "AS псевдоним" + поля
     * @return 
     */
    public final Select select (Class t, String... c) {
        return new Select (t (t), c);
    }
    
    /**
     * Генератор SELECT
     * @param t описание 1-й таблицы в разделе FROM 
     * @param c "AS псевдоним" + поля
     * @return 
     */
    public final Select select (Table t, String... c) {
        return new Select (t, c);
    }
    
    public final Select selectVoc (Table t, String... c) {
        String [] ss = new String [c.length + 2];
        for (int i = 0; i < c.length; i ++) ss [i] = c [i];
        final List<Col> pk = t.getPk ();
        if (pk.size () != 1) throw new IllegalArgumentException ("References to tables with vector PKs are not yet supported");
        String pkName = pk.get (0).getName ();
        if (!"id".equals (pkName)) pkName = pkName + " AS id";
        ss [c.length] = pkName;
        ss [1 + c.length] = DEFAULT_ORDER_FIELD;
        return new Select (t, ss).orderBy (DEFAULT_ORDER_FIELD);
    }

    public final Select selectVoc (Class t, String... c) {
        return selectVoc (t (t), c);
    }
    
    public final Select selectVoc (String t, String... c) {
        return selectVoc (t (t), c);
    }
    
    /**
     * Генератор SELECT на поиск записи заданной таблицы по первичному ключу
     * @param t описание таблицы
     * @param id значение первичного ключа
     * @param names "AS псевдоним" + поля
     * @return 
     */
    public final Get get (Table t, Object id, String... c) {
        return new Get (t, id, c);
    }
    
    /**
     * Генератор SELECT на поиск записи заданной таблицы по первичному ключу
     * @param t описание таблицы
     * @param id значение первичного ключа
     * @param names "AS псевдоним" + поля
     * @return 
     */
    public final Get get (Class t, Object id, String... c) {
        return new Get (t (t), id, c);
    }
    
    /**
     * Генератор SELECT на поиск записи заданной таблицы по первичному ключу
     * @param t имя таблицы
     * @param id значение первичного ключа
     * @param names "AS псевдоним" + поля
     * @return 
     */
    public final Get get (String t, Object id, String... c) {
        return new Get (t (t), id, c);
    }

}