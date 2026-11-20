package com.debugmock.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Called from instrumented bytecode to turn a JSON value into the target object/array. */
public final class MockValues {

    /**
     * Gson's built-in adapters only accept strings for dates and know nothing about java.time.
     * Payloads (and the IDE's JSON skeleton) also use epoch millis, so accept both for every
     * supported temporal type.
     */
    private static final JsonDeserializer<Object> DATE = new JsonDeserializer<Object>() {
        @Override
        public Object deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            long millis = millis(json);
            if (type == java.sql.Timestamp.class) {
                return new java.sql.Timestamp(millis);
            }
            if (type == java.sql.Date.class) {
                return new java.sql.Date(millis);
            }
            return new Date(millis);
        }
    };

    private static final JsonDeserializer<Object> TEMPORAL = new JsonDeserializer<Object>() {
        @Override
        public Object deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            String text = text(json);
            Long number = number(json);
            try {
                if (type == Instant.class) {
                    return number == null ? Instant.parse(text) : Instant.ofEpochMilli(number);
                }
                if (type == LocalDate.class) {
                    return number == null ? LocalDate.parse(text)
                            : LocalDate.ofEpochDay(number / 86400000L);
                }
                if (type == LocalTime.class) {
                    return number == null ? LocalTime.parse(text)
                            : LocalTime.ofNanoOfDay((number % 86400000L) * 1000000L);
                }
                if (type == LocalDateTime.class) {
                    if (number != null) {
                        return LocalDateTime.ofInstant(Instant.ofEpochMilli(number), ZoneOffset.UTC);
                    }
                    try {
                        return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    } catch (RuntimeException e) {
                        return LocalDateTime.ofInstant(Instant.parse(text), ZoneOffset.UTC);
                    }
                }
                if (type == OffsetDateTime.class) {
                    return number == null ? OffsetDateTime.parse(text)
                            : OffsetDateTime.ofInstant(Instant.ofEpochMilli(number), ZoneOffset.UTC);
                }
                if (type == ZonedDateTime.class) {
                    return number == null ? ZonedDateTime.parse(text)
                            : ZonedDateTime.ofInstant(Instant.ofEpochMilli(number), ZoneOffset.UTC);
                }
                if (type == Duration.class) {
                    return number == null ? Duration.parse(text) : Duration.ofMillis(number);
                }
                if (type == Period.class) {
                    return Period.parse(text);
                }
                if (type == YearMonth.class) {
                    return number == null ? YearMonth.parse(text)
                            : YearMonth.from(Instant.ofEpochMilli(number).atZone(ZoneOffset.UTC));
                }
                if (type == MonthDay.class) {
                    return MonthDay.parse(text);
                }
                if (type == Year.class) {
                    return number == null ? Year.parse(text)
                            : Year.of(Instant.ofEpochMilli(number).atZone(ZoneOffset.UTC).getYear());
                }
            } catch (RuntimeException e) {
                throw new JsonParseException("Cannot parse " + type + " from " + json + ": " + e.getMessage());
            }
            throw new JsonParseException("Unsupported temporal type: " + type);
        }
    };

    // Lenient parsing tolerates slightly malformed payloads. The adapters above cover numbers and
    // strings for every date/time type; Gson's other adapters handle private fields, Lombok @Data,
    // extra keys and numeric strings for boxed numbers.
    private static final Gson GSON = new GsonBuilder()
            .setLenient()
            .serializeNulls()
            .registerTypeAdapter(Date.class, DATE)
            .registerTypeAdapter(java.sql.Timestamp.class, DATE)
            .registerTypeAdapter(java.sql.Date.class, DATE)
            .registerTypeAdapter(Instant.class, TEMPORAL)
            .registerTypeAdapter(LocalDate.class, TEMPORAL)
            .registerTypeAdapter(LocalTime.class, TEMPORAL)
            .registerTypeAdapter(LocalDateTime.class, TEMPORAL)
            .registerTypeAdapter(OffsetDateTime.class, TEMPORAL)
            .registerTypeAdapter(ZonedDateTime.class, TEMPORAL)
            .registerTypeAdapter(Duration.class, TEMPORAL)
            .registerTypeAdapter(Period.class, TEMPORAL)
            .registerTypeAdapter(YearMonth.class, TEMPORAL)
            .registerTypeAdapter(MonthDay.class, TEMPORAL)
            .registerTypeAdapter(Year.class, TEMPORAL)
            .create();

    private MockValues() {
    }

    /**
     * Deserializes JSON into the requested type. Never throws: a broken payload yields a neutral
     * value of the right shape so the mocked method still returns normally.
     */
    public static Object fromJson(String typeName, String json) {
        Class<?> type = null;
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) {
                loader = MockValues.class.getClassLoader();
            }
            type = Class.forName(typeName, false, loader);
            Object value = GSON.fromJson(json, type);
            return value != null ? value : neutral(type);
        } catch (Throwable t) {
            System.err.println("[DebugMock] cannot convert value to " + typeName + ": " + t);
            return type == null ? null : neutral(type);
        }
    }

    /** A harmless stand-in with the requested shape, used when JSON cannot be deserialized. */
    private static Object neutral(Class<?> type) {
        try {
            if (type.isArray()) {
                return Array.newInstance(type.getComponentType(), 0);
            }
            if (Collection.class.isAssignableFrom(type)) {
                return new ArrayList<Object>();
            }
            if (Map.class.isAssignableFrom(type)) {
                return new LinkedHashMap<Object, Object>();
            }
            if (type == Boolean.class) {
                return Boolean.FALSE;
            }
            if (Number.class.isAssignableFrom(type)) {
                return Integer.valueOf(0);
            }
            Constructor<?> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long millis(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return 0L;
        }
        if (!element.isJsonPrimitive()) {
            throw new JsonParseException("Expected a timestamp, got " + element);
        }
        Long number = number(element);
        if (number != null) {
            return number;
        }
        String text = element.getAsString().trim();
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            // ISO / formatted date below
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (RuntimeException ignored) {
            // Other patterns below
        }
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                return format.parse(text).getTime();
            } catch (ParseException ignored) {
                // try the next pattern
            }
        }
        throw new JsonParseException("Cannot parse timestamp: " + text);
    }

    private static Long number(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            return primitive.getAsLong();
        }
        String text = primitive.getAsString().trim();
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonElement element) {
        return element == null || element.isJsonNull() ? "" : element.getAsString().trim();
    }
}
