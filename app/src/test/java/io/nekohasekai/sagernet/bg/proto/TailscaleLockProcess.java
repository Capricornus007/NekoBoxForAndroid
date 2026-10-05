package io.nekohasekai.sagernet.bg.proto;

import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/** Separate JVM probe for the OS lock, without sharing the application's reservation table. */
public final class TailscaleLockProcess {
    public static void main(String[] args) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(args[0], "rw")) {
            try (FileLock lock = file.getChannel().tryLock()) {
                System.out.println(lock == null ? "busy" : "acquired");
                if (lock != null && args.length > 1) {
                    System.out.flush();
                    System.in.read();
                }
            }
        }
    }
}
