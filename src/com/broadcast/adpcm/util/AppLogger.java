package com.broadcast.adpcm.util;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AppLogger - Logging sistem (info, error, debugging).
 * Mendukung multiple log levels, file output, dan async logging.
 */
public final class AppLogger {
    
    // Log levels
    public enum Level {
        DEBUG(0, "DEBUG"),
        INFO(1, "INFO"),
        WARN(2, "WARN"),
        ERROR(3, "ERROR"),
        FATAL(4, "FATAL");
        
        private final int value;
        private final String name;
        
        Level(int value, String name) {
            this.value = value;
            this.name = name;
        }
        
        public int getValue() { return value; }
        public String getName() { return name; }
    }
    
    // Default configuration
    private static final Level DEFAULT_LEVEL = Level.INFO;
    private static final int DEFAULT_MAX_QUEUE_SIZE = 10000;
    private static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS";
    private static final String LOG_FILE_NAME = "adpcm-server.log";
    
    // Singleton instance
    private static volatile AppLogger instance;
    
    // Configuration
    private Level logLevel;
    private boolean logToConsole;
    private boolean logToFile;
    private boolean asyncLogging;
    private String logFilePath;
    private String dateFormat;
    
    // Async logging components
    private ConcurrentLinkedQueue<LogEntry> logQueue;
    private Thread asyncLoggerThread;
    private AtomicBoolean isRunning;
    
    // File output
    private PrintWriter fileWriter;
    private final Object fileLock = new Object();
    
    // Statistics
    private long totalLogs = 0;
    private long errorCount = 0;
    private long warnCount = 0;
    
    /**
     * Private constructor (singleton pattern).
     */
    private AppLogger() {
        this.logLevel = DEFAULT_LEVEL;
        this.logToConsole = true;
        this.logToFile = false;
        this.asyncLogging = true;
        this.logFilePath = LOG_FILE_NAME;
        this.dateFormat = DEFAULT_DATE_FORMAT;
        this.logQueue = new ConcurrentLinkedQueue<>();
        this.isRunning = new AtomicBoolean(true);
        
        // Start async logger thread
        if (asyncLogging) {
            startAsyncLogger();
        }
    }
    
    /**
     * Get singleton instance.
     */
    public static AppLogger getInstance() {
        if (instance == null) {
            synchronized (AppLogger.class) {
                if (instance == null) {
                    instance = new AppLogger();
                }
            }
        }
        return instance;
    }
    
    /**
     * Initialize logger with custom configuration.
     */
    public static void init(Level level, boolean console, boolean file, String filePath) {
        AppLogger logger = getInstance();
        logger.logLevel = level;
        logger.logToConsole = console;
        logger.logToFile = file;
        
        if (filePath != null && !filePath.isEmpty()) {
            logger.logFilePath = filePath;
        }
        
        if (file) {
            logger.openLogFile();
        }
    }
    
    /**
     * Start asynchronous logger thread.
     */
    private void startAsyncLogger() {
        asyncLoggerThread = new Thread(() -> {
            while (isRunning.get()) {
                try {
                    LogEntry entry = logQueue.poll();
                    if (entry != null) {
                        writeLogEntry(entry);
                    } else {
                        Thread.sleep(10);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("Async logger error: " + e.getMessage());
                }
            }
            
            // Flush remaining entries
            LogEntry entry;
            while ((entry = logQueue.poll()) != null) {
                writeLogEntry(entry);
            }
        }, "AsyncLogger");
        
        asyncLoggerThread.setDaemon(true);
        asyncLoggerThread.start();
    }
    
    /**
     * Open log file for writing.
     */
    private void openLogFile() {
        try {
            synchronized (fileLock) {
                if (fileWriter != null) {
                    fileWriter.close();
                }
                
                File logFile = new File(logFilePath);
                File parentDir = logFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                
                fileWriter = new PrintWriter(new BufferedWriter(
                    new FileWriter(logFile, true)
                ));
            }
        } catch (IOException e) {
            System.err.println("Failed to open log file: " + e.getMessage());
            logToFile = false;
        }
    }
    
    /**
     * Close log file.
     */
    private void closeLogFile() {
        synchronized (fileLock) {
            if (fileWriter != null) {
                fileWriter.flush();
                fileWriter.close();
                fileWriter = null;
            }
        }
    }
    
    /**
     * Write log entry to console and/or file.
     */
    private void writeLogEntry(LogEntry entry) {
        String formatted = entry.format(dateFormat);
        
        // Write to console
        if (logToConsole) {
            if (entry.level.getValue() >= Level.ERROR.getValue()) {
                System.err.println(formatted);
            } else {
                System.out.println(formatted);
            }
        }
        
        // Write to file
        if (logToFile && fileWriter != null) {
            synchronized (fileLock) {
                fileWriter.println(formatted);
                fileWriter.flush();
            }
        }
    }
    
    /**
     * Log a message with specific level (instance method).
     */
    private void log(Level level, String message) {
        if (level.getValue() < logLevel.getValue()) {
            return;
        }
        
        totalLogs++;
        if (level == Level.ERROR) errorCount++;
        if (level == Level.WARN) warnCount++;
        
        LogEntry entry = new LogEntry(level, message, null, Thread.currentThread().getName());
        
        if (asyncLogging && isRunning.get()) {
            if (logQueue.size() < DEFAULT_MAX_QUEUE_SIZE) {
                logQueue.offer(entry);
            } else {
                // Drop log if queue is full
                System.err.println("Log queue full, dropping log entry");
            }
        } else {
            writeLogEntry(entry);
        }
    }
    
    /**
     * Log with throwable (instance method).
     */
    private void log(Level level, String message, Throwable throwable) {
        if (level.getValue() < logLevel.getValue()) {
            return;
        }
        
        totalLogs++;
        if (level == Level.ERROR) errorCount++;
        if (level == Level.WARN) warnCount++;
        
        LogEntry entry = new LogEntry(level, message, throwable, Thread.currentThread().getName());
        
        if (asyncLogging && isRunning.get()) {
            if (logQueue.size() < DEFAULT_MAX_QUEUE_SIZE) {
                logQueue.offer(entry);
            } else {
                System.err.println("Log queue full, dropping log entry");
                if (throwable != null) {
                    throwable.printStackTrace();
                }
            }
        } else {
            writeLogEntry(entry);
            if (throwable != null && logToConsole) {
                throwable.printStackTrace();
            }
        }
    }
    
    // ==================== STATIC PUBLIC METHODS ====================
    
    public static void debug(String message) {
        getInstance().log(Level.DEBUG, message);
    }
    
    public static void debug(String message, Throwable t) {
        getInstance().log(Level.DEBUG, message, t);
    }
    
    public static void info(String message) {
        getInstance().log(Level.INFO, message);
    }
    
    public static void info(String message, Throwable t) {
        getInstance().log(Level.INFO, message, t);
    }
    
    public static void warn(String message) {
        getInstance().log(Level.WARN, message);
    }
    
    public static void warn(String message, Throwable t) {
        getInstance().log(Level.WARN, message, t);
    }
    
    public static void error(String message) {
        getInstance().log(Level.ERROR, message);
    }
    
    public static void error(String message, Throwable t) {
        getInstance().log(Level.ERROR, message, t);
    }
    
    public static void fatal(String message) {
        getInstance().log(Level.FATAL, message);
    }
    
    public static void fatal(String message, Throwable t) {
        getInstance().log(Level.FATAL, message, t);
    }
    
    /**
     * Log with format string (like printf).
     */
    public static void logf(Level level, String format, Object... args) {
        getInstance().log(level, String.format(format, args));
    }
    
    /**
     * Shutdown logger.
     */
    public static void shutdown() {
        AppLogger logger = getInstance();
        logger.isRunning.set(false);
        
        if (logger.asyncLoggerThread != null) {
            logger.asyncLoggerThread.interrupt();
            try {
                logger.asyncLoggerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        logger.closeLogFile();
        
        info("Logger shutdown. Total logs: " + logger.totalLogs + 
             ", Errors: " + logger.errorCount + 
             ", Warnings: " + logger.warnCount);
    }
    
    /**
     * Set log level.
     */
    public static void setLogLevel(Level level) {
        getInstance().logLevel = level;
    }
    
    /**
     * Enable/disable console logging.
     */
    public static void setConsoleLogging(boolean enabled) {
        getInstance().logToConsole = enabled;
    }
    
    /**
     * Enable/disable file logging.
     */
    public static void setFileLogging(boolean enabled, String filePath) {
        AppLogger logger = getInstance();
        logger.logToFile = enabled;
        if (filePath != null) {
            logger.logFilePath = filePath;
        }
        if (enabled) {
            logger.openLogFile();
        } else {
            logger.closeLogFile();
        }
    }
    
    /**
     * Get statistics.
     */
    public static String getStats() {
        AppLogger logger = getInstance();
        return String.format("LogStats[total=%d, errors=%d, warns=%d, queue=%d]",
            logger.totalLogs, logger.errorCount, logger.warnCount, logger.logQueue.size());
    }
    
    /**
     * Quick performance test.
     */
    public static void performanceTest(int iterations) {
        AppLogger logger = getInstance();
        long start = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            info("Performance test message " + i);
        }
        
        long elapsed = System.nanoTime() - start;
        double opsPerSec = (iterations * 1000000000.0) / elapsed;
        
        System.out.printf("Performance: %.2f logs/sec%n", opsPerSec);
    }
    
    /**
     * LogEntry inner class.
     */
    private static class LogEntry {
        private final Level level;
        private final String message;
        private final Throwable throwable;
        private final String threadName;
        private final long timestamp;
        
        LogEntry(Level level, String message, Throwable throwable, String threadName) {
            this.level = level;
            this.message = message;
            this.throwable = throwable;
            this.threadName = threadName;
            this.timestamp = System.currentTimeMillis();
        }
        
        String format(String dateFormat) {
            SimpleDateFormat sdf = new SimpleDateFormat(dateFormat);
            String time = sdf.format(new Date(timestamp));
            StringBuilder sb = new StringBuilder();
            
            sb.append("[").append(time).append("] ");
            sb.append("[").append(threadName).append("] ");
            sb.append("[").append(level.getName()).append("] ");
            sb.append(message);
            
            if (throwable != null) {
                sb.append("\n");
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                sb.append(sw.toString());
            }
            
            return sb.toString();
        }
    }
}