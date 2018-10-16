package ru.eludia.base.db.util;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

public class TypeConverter {
    
    private static final Logger logger = Logger.getLogger (TypeConverter.class.getName ());

    private final static char[] hexArray = "0123456789ABCDEF".toCharArray ();
    private final static Pattern datePattern = Pattern.compile("\\d{2}.\\d{2}.\\d{4}");
    static DatatypeFactory dtf;        
    
    static {
                
        try {
            dtf = DatatypeFactory.newInstance ();
        }
        catch (DatatypeConfigurationException ex) {
            throw new IllegalStateException ("Cannot create DatatypeFactory", ex);
        }

    }
    
    private static final Map primitiveWrapperMap = new HashMap();
    static {
         primitiveWrapperMap.put(Boolean.TYPE, Boolean.class);
         primitiveWrapperMap.put(Byte.TYPE, Byte.class);
         primitiveWrapperMap.put(Character.TYPE, Character.class);
         primitiveWrapperMap.put(Short.TYPE, Short.class);
         primitiveWrapperMap.put(Integer.TYPE, Integer.class);
         primitiveWrapperMap.put(Long.TYPE, Long.class);
         primitiveWrapperMap.put(Double.TYPE, Double.class);
         primitiveWrapperMap.put(Float.TYPE, Float.class);
    }
    
    /**
     * Преобразует произвольный список Map'ов со строковыми ключами в спсиок JSON, открытый для дозаписи
     * @param l исходный список (полученный DB.HASH)
     * @return тот же список в JSON
     */
    public final static JsonArrayBuilder JsonArrayBuilder (List<Map<String, Object>> l) {
        JsonArrayBuilder ab = Json.createArrayBuilder ();
        l.forEach (map -> ab.add (JsonObjectBuilder (map)));
        return ab;
    }

    /**
     * Преобразует произвольный Map со строковыми ключами в объект JSON, открытый для дозаписи
     * @param m набор пар ключ/значение
     * @return тот же набор, но в JSON и за исключением пустых значений
     */
    public final static JsonObjectBuilder JsonObjectBuilder (Map<String, Object> m) {
        
        JsonObjectBuilder job = Json.createObjectBuilder ();
               
        m.entrySet ().forEach (kv -> {
            
            Object value = kv.getValue ();
            
            if (value == null) return;
            
            if (value instanceof JsonValue) {
                 job.add (kv.getKey (), (JsonValue) value);
            }
            else if (value instanceof Integer) {
                 job.add (kv.getKey (), (Integer) value);
            }
            else {
                 job.add (kv.getKey (), value.toString ());
            }
            
        });
        
        return job;
        
    }
    
    /**
     * Конструктор UUID из HEX-строки без разделителей
     * @param 2 Строка из 32 шестнадцатеричних цифр
     * @return Соответствующий UUID
     */
    public final static UUID UUIDFromHex (String s) {
        return UUID (bytesFromHex (s));
    }
    
    /**
     * Конструктор UUID, который забыли включить в J2SE
     * @param bytes Ровно 16 байт. Любые.
     * @return Соответствующий UUID
     */
    public final static UUID UUID (byte[] bytes) {
        
        if (bytes.length != 16) throw new IllegalArgumentException();
        int i = 0;
        long msl = 0;
        for (; i < 8; i++) msl = (msl << 8) | (bytes[i] & 0xFF);
        long lsl = 0;
        for (; i < 16; i++) lsl = (lsl << 8) | (bytes[i] & 0xFF);
        return new UUID(msl, lsl);
        
    }    
    
    /**
     * Перевод даты/времени из JDBC в JAXB.
     * @param ts дата/время из JDBC
     * @return дата/время для JAXB
     */
    public final static XMLGregorianCalendar XMLGregorianCalendar (Timestamp ts) {
        GregorianCalendar gc = new GregorianCalendar ();
        gc.setTime (new java.util.Date (ts.getTime ()));
        return dtf.newXMLGregorianCalendar (gc);
    }
    
    /**
     * Конструктор JAXB-дат из строк
     * @param s Строка с датой в XML-формате
     * @return Дата в виде, пригодном для использования в JAXB
     */
    public final static XMLGregorianCalendar XMLGregorianCalendar (String s) {
        return dtf.newXMLGregorianCalendar (s);
    }
    
    /**
     * Шестнадцатеричное представление бинарных данных
     * @param bytes массив байт
     * @return строка, изображающая исходный массив в шестнадцатеричном представлении (1 байт на входе = 2 цифры 0-F на выходе)
     */
    public static final String hex (byte[] bytes) {
        char [] hexChars = new char [bytes.length * 2];
        for (int j = 0; j < bytes.length; j ++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String (hexChars);
    }  
    
    /**
     * Шестнадцатеричное представление бинарных данных
     * @param строка, изображающая исходный массив в шестнадцатеричном представлении 
     * @return bytes массив байт (2 цифры 0-F на входе = 1 байт на выходе)
     */
    public static byte[] bytesFromHex (String s) {        
        int len = s.length ();        
        byte[] data = new byte [len / 2];        
        for (int i = 0; i < len; i += 2) {
            data [i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }        
        return data;        
    }
    
    /**
     * Вспомогательный метод для передачи бинарных данных в BLOB-параметры
     * @param v массив байт
     * @return представляющий его поток
     */
    public static final InputStream binaryStream (Object v) {

        if (v instanceof byte []) return new java.io.ByteArrayInputStream ((byte []) v);
        
        return (InputStream) v;
        
    }
    
    /**
     * Получение логического значения -- при условии того, что 
     * истина в строковом представлении всегда выглядит как "1"
     * @param v исходное значение
     * @return true, если v приводится к значению "1"; 
     * иначе -- false (в том числе для null!)
     */
    public static final Boolean bool (Object v) {
        if (v == null) return false;
        return "1".equals (v.toString ());
    }
    
    public static final Timestamp timestamp (Object v) {
        
        if (v instanceof XMLGregorianCalendar) return new Timestamp (((XMLGregorianCalendar) v).toGregorianCalendar ().getTimeInMillis ());
        
        String s = v.toString ().replace ("+03:00", "");

        if (s.length () < 10) throw new IllegalArgumentException ("Invalid date: '" + s + "'");
        
        if (s.length () == 10) {
            if (datePattern.matcher(s).matches()) {
                String[] dateParts = s.split("\\.");
                s = dateParts[2].concat("-").concat(dateParts[1]).concat("-").concat(dateParts[0]);
            }
            return Timestamp.valueOf (s + " 00:00:00");
        }
        
        if (s.charAt (10) == ' ') return Timestamp.valueOf (s);
        
        return Timestamp.valueOf (s.replace ('T', ' '));        
        
    }
    
    /**
     * Восстанавливает JSON-объект из его сериализованной формы
     * @param s Корректный JSON, начинающийся с '{'.
     * @return Распакованный объект
     */
    public static final JsonObject JsonObject (String s) {
        
        try (StringReader sr = new StringReader (s)) {
            
            try (JsonReader jr = Json.createReader (sr)) {
                
                return jr.readObject ();
                
            }

        }
        
    }
    
    /**
     * Переводит значение JSON в объект java.
     * @param v значение из 
     * @return Распакованный объект
     */
    public static final Object object (JsonValue v) {
        
        if (v == null) return null;
        
        switch (v.getValueType ()) {
            case NULL: return null;
            case TRUE: return 1;
            case FALSE: return 0;
            case STRING: return ((JsonString) v).getString ();
            case NUMBER: 
                JsonNumber n = (JsonNumber) v;
                return n.isIntegral () ? n.intValueExact () : n.bigDecimalValue ();
        }
        
        return null;
        
    }
    
    /**
     * Переводит запись, доступную как хэш "ключ-значение" 
     * (полученную, например, как DB.getMap) в объект javaBean.
     * 
     * А именно: создаётся пустой javaBean, а потом на каждое его свойство,
     * для которого находится соответствующая компонента map, вызывается setter.
     * 
     * @param clazz класс javaBean, экземпляр которого надо создать
     * @param values значения полей, которые надо установить
     * 
     * @return javaBean с требуемыми значениями полей
     */
    public static final Object javaBean (Class clazz, Map<String, Object> values) {
                
        try {
            
            Object javaBean = clazz.getConstructor ().newInstance ();
            
            BeanInfo info = Introspector.getBeanInfo (clazz);
            
            PropertyDescriptor [] props = info.getPropertyDescriptors();
            
            for (PropertyDescriptor pd: props) {

                String name = pd.getName ();

                Object value = values.get (name.toLowerCase ());
                
                if (value == null) continue;
                
                final Method writeMethod = pd.getWriteMethod ();

                if (writeMethod == null) continue;

                Class<?> type = writeMethod.getParameterTypes () [0];
                
                if (type.isPrimitive())
                    type = (Class)primitiveWrapperMap.get(type);
                
                if (value.getClass().equals(type))
                    writeMethod.invoke(javaBean, value);
                else if (String.class.equals (type)) {
                    final String s = value.toString ();
                    if (s.isEmpty ()) continue;
                    writeMethod.invoke (javaBean, s);
                }
                else if (Boolean.class.equals (type)) {
                    final String s = value.toString ();
                    if (s.isEmpty ()) continue;
                    switch (s) {
                        case "1":
                        case "true":
                        case "TRUE":
                        case "True":
                            writeMethod.invoke (javaBean, true);
                            break;
                        default: 
                            writeMethod.invoke (javaBean, false);
                    }
                }
                else if (XMLGregorianCalendar.class.equals (type)) {
                    writeMethod.invoke (javaBean, XMLGregorianCalendar (value.toString ().replace (' ', 'T')));
                }
                else if (Byte.class.equals(type)
                        || Short.class.equals(type)
                        || Integer.class.equals(type)
                        || Long.class.equals(type)
                        || Double.class.equals(type)
                        || Float.class.equals(type)
                        || BigDecimal.class.equals(type)) {
                        writeMethod.invoke(javaBean, type.getMethod("valueOf", String.class).invoke(value, value.toString()));
                }
                else {
                    logger.warning ("javaBean property setting not supported for " + type.getName ());
                }
                    
            }
            
            return javaBean;
            
        }
        catch (Exception ex) {
            throw new IllegalStateException (ex);
        }
                
    }
    
    
}