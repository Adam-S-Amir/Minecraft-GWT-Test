/*
 * Decompiled with CFR 0.152.
 */
package anjocaido.console;

import anjocaido.console.OutputConsole;

static final class OutputConsole.2
implements Runnable {
    OutputConsole.2() {
    }

    public void run() {
        new OutputConsole().setVisible(true);
    }
}
