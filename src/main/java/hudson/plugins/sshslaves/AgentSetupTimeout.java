package hudson.plugins.sshslaves;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import jenkins.util.Timer;

/**
 * Limits how long the agent setup can take once the SSH connection is open.
 * The connection has no read timeout (JENKINS-48618), so if the agent stops responding during the setup the launch
 * would wait forever, and every later launch of the agent would wait for it.
 * When the time is up the launching thread is interrupted, which makes the waits on the connection fail.
 * Closing the connection instead would not work, as {@code Connection.close()} waits for the same lock as a
 * {@code Connection.openSession()} that waits for the agent.
 */
final class AgentSetupTimeout {

    private enum State {
        RUNNING,
        FINISHED,
        TIMED_OUT
    }

    /**
     * Once the time is up, how often the launching thread is interrupted again, in case it handled the interruption
     * and went on waiting for the agent.
     */
    private static final long INTERRUPT_INTERVAL_SECONDS = 1;

    private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);

    @CheckForNull
    private ScheduledFuture<?> interrupter;

    private AgentSetupTimeout() {}

    /**
     * Starts the timeout for the current thread.
     *
     * @param timeoutSeconds the time the setup can take, 0 or less to wait forever.
     * @return the timeout, to be finished by the same thread once the setup is over.
     */
    static AgentSetupTimeout start(int timeoutSeconds) {
        AgentSetupTimeout timeout = new AgentSetupTimeout();
        if (timeoutSeconds > 0) {
            Thread thread = Thread.currentThread();
            timeout.interrupter = Timer.get()
                    .scheduleWithFixedDelay(
                            () -> timeout.expire(thread), timeoutSeconds, INTERRUPT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
        return timeout;
    }

    private void expire(Thread thread) {
        state.compareAndSet(State.RUNNING, State.TIMED_OUT);
        if (state.get() == State.TIMED_OUT) {
            thread.interrupt();
        }
    }

    /**
     * Stops the timeout once the setup is over, whether it succeeded or not.
     *
     * @return {@code true} if the setup finished in time, {@code false} if the time ran out, in which case the setup
     *     has to be considered failed and the interrupted status of the thread is cleared.
     */
    boolean finish() {
        if (interrupter != null) {
            interrupter.cancel(false);
        }
        if (state.compareAndSet(State.RUNNING, State.FINISHED)) {
            return true;
        }
        Thread.interrupted();
        return false;
    }
}
