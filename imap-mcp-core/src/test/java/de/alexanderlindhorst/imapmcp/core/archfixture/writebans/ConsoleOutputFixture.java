package de.alexanderlindhorst.imapmcp.core.archfixture.writebans;

/** Violates rule (f): writes to System.out/System.err and prints a stack trace. Excluded from the real scan. */
public class ConsoleOutputFixture {
    void out() {
        System.out.println("x");
    }

    void err() {
        System.err.println("x");
    }

    void trace(Exception e) {
        e.printStackTrace();
    }
}
