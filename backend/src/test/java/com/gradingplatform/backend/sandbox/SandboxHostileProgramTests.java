package com.gradingplatform.backend.sandbox;

import static com.gradingplatform.backend.sandbox.DockerCli.docker;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gradingplatform.backend.TestcontainersConfiguration;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 4.7a: the hostile-program suite. Every test runs a real hostile program in a real container
 * through {@link DockerCliSandboxRunner} and checks two things: the program was <em>contained</em>
 * (it did not get what it reached for) and it was <em>reported</em> with the right status. After every
 * test no container and no run directory may be left, and the sandbox must still run an honest program.
 *
 * <p>The tests are grouped by the threat they stand for, the categories used by published sandbox
 * test suites, so the thesis can map each test to a threat and to the restriction that stops it:
 *
 * <table>
 * <caption>Threat map</caption>
 * <tr><th>Category</th><th>Hostile program</th><th>Stopped by</th></tr>
 * <tr><td>Resource exhaustion</td><td>infinite loop, sleep forever, blocked forever</td><td>wall-clock timeout + {@code docker kill} (4.3b)</td></tr>
 * <tr><td>Resource exhaustion</td><td>memory bomb, slow growth that would spill into swap</td><td>{@code --memory} = {@code --memory-swap} (4.3a, 4.5a)</td></tr>
 * <tr><td>Resource exhaustion</td><td>fork bomb, thread bomb</td><td>{@code --pids-limit} (4.3a)</td></tr>
 * <tr><td>Resource exhaustion</td><td>giant output, filling {@code /tmp}</td><td>output cap (4.3c), tmpfs size (4.3a)</td></tr>
 * <tr><td>Filesystem manipulation</td><td>write to the root filesystem, to the submission, to {@code /proc}; run from {@code /tmp}; mount</td><td>{@code --read-only}, read-only bind mount, {@code noexec}, no capabilities</td></tr>
 * <tr><td>Sensitive information exposure</td><td>read {@code /etc/shadow}, the Docker socket, host paths, other submissions, the backend's secrets</td><td>non-root user, no host mounts but one directory, no environment passed on</td></tr>
 * <tr><td>External communication</td><td>TCP, UDP, DNS, raw sockets, the host's own ports</td><td>{@code --network none}, no {@code NET_RAW}</td></tr>
 * <tr><td>Dangerous operations</td><td>become root, {@code chroot}, {@code mount}</td><td>{@code --cap-drop ALL}, {@code no-new-privileges}, user {@code 10001}</td></tr>
 * </table>
 *
 * <p>Older tests pin the detail of some rows ({@code SandboxTimeoutTests}, {@code SandboxExitStatusTests},
 * {@code SandboxOutputCapTests}, {@code SandboxFlagsTests} read the flags back); here each row is run
 * once as a whole program. The property that hidden test data never exists in the container is 4.7d.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SandboxHostileProgramTests {

    /** A run may take its limit, the start-up allowance and a few seconds to kill and clean up. */
    private static final Duration KILL_ALLOWANCE = Duration.ofSeconds(8);

    private static final ExecutionLimits PROBE = new ExecutionLimits(20_000, 128);

    /**
     * Python helpers shared by the probes: {@code attempt} prints {@code label=ALLOWED} when the
     * action worked and {@code label=<errno name>} when the system refused it. No backslashes, because
     * this is a Java text block.
     */
    private static final String PRELUDE = """
            import errno, os, socket, sys

            def attempt(label, action):
                try:
                    action()
                    print(label + '=ALLOWED')
                except OSError as e:
                    print(label + '=' + errno.errorcode.get(e.errno, str(e.errno)))
                except Exception as e:
                    print(label + '=' + type(e).__name__)

            def write(path):
                with open(path, 'w') as f:
                    f.write('x')

            """;

    @Autowired
    SandboxProperties languages;

    @TempDir
    Path workRoot;

    DockerCliSandboxRunner runner;

    Set<String> containersBefore;

    @BeforeEach
    void runnerWithAPrivateWorkRoot() {
        SandboxTestImages.ensureBuilt(languages, "PYTHON");
        SandboxTestImages.ensureBuilt(languages, "JAVA");
        runner = new DockerCliSandboxRunner(languages, workRoot);
        containersBefore = sandboxContainers();
    }

    @AfterEach
    void nothingIsLeftBehind() throws IOException {
        assertThat(sandboxContainers()).as("sandbox containers").isEqualTo(containersBefore);
        try (Stream<Path> entries = Files.list(workRoot)) {
            assertThat(entries.map(p -> p.getFileName().toString()).filter(name -> name.startsWith("run-")))
                    .as("run directories")
                    .isEmpty();
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private SandboxResult python(String code, ExecutionLimits limits) {
        return python(code, "", limits);
    }

    private SandboxResult python(String code, String stdin, ExecutionLimits limits) {
        return runner.run(new SandboxRequest("PYTHON", List.of(new SourceFile("main.py", code)), stdin, limits));
    }

    private SandboxResult java(String code, ExecutionLimits limits) {
        return runner.run(new SandboxRequest("JAVA", List.of(new SourceFile("Main.java", code)), "", limits));
    }

    private SandboxResult probe(String body) {
        return probe(body, "");
    }

    private SandboxResult probe(String body, String stdin) {
        SandboxResult result = python(PRELUDE + body, stdin, PROBE);
        assertThat(result.status())
                .as("the probe itself must run: " + result.stderr())
                .isEqualTo(SandboxStatus.OK);
        return result;
    }

    private static Map<String, String> facts(SandboxResult result) {
        Map<String, String> facts = new LinkedHashMap<>();
        result.stdout().lines().forEach(line -> {
            int at = line.indexOf('=');
            if (at > 0) {
                facts.put(line.substring(0, at), line.substring(at + 1));
            }
        });
        return facts;
    }

    /** Every label was tried and the system said no (anything but {@code ALLOWED}). */
    private static void assertRefused(Map<String, String> facts, String... labels) {
        for (String label : labels) {
            assertThat(facts).as("the probe reported " + label).containsKey(label);
            assertThat(facts.get(label)).as(label).isNotEqualTo("ALLOWED");
        }
    }

    private static Set<String> sandboxContainers() {
        DockerCli.Output out = docker(
                Duration.ofSeconds(60), "ps", "--all", "--quiet", "--filter", "label=" + DockerRunArguments.LABEL);
        assertThat(out.exitCode()).as(out.stderr()).isZero();
        return out.stdout().lines().filter(line -> !line.isBlank()).collect(Collectors.toSet());
    }

    private static void assertStoppedInTime(SandboxResult result, long timeLimitMs, Duration elapsed) {
        assertThat(result.status()).isEqualTo(SandboxStatus.TIMEOUT);
        assertThat(result.exitCode()).isNull();
        assertThat(result.completedCleanly()).isFalse();
        assertThat(elapsed)
                .isLessThan(Duration.ofMillis(timeLimitMs)
                        .plus(DockerCliSandboxRunner.START_GRACE)
                        .plus(KILL_ALLOWANCE));
    }

    /** After a hostile program the sandbox still works: an honest one gets its answer. */
    private void assertSandboxStillGradesAnHonestProgram() {
        SandboxResult honest = python("print(6 * 7)\n", new ExecutionLimits(5_000, 128));

        assertThat(honest.status()).isEqualTo(SandboxStatus.OK);
        assertThat(honest.stdout().strip()).isEqualTo("42");
    }

    // --- resource exhaustion: time -----------------------------------------------------------------

    @Test
    void anInfiniteLoopIsKilledAndReportedAsATimeout() {
        long started = System.nanoTime();

        SandboxResult result = python("while True:\n    pass\n", new ExecutionLimits(1_000, 128));

        assertStoppedInTime(result, 1_000, Duration.ofNanos(System.nanoTime() - started));
    }

    @Test
    void aProgramThatSleepsForeverIsKilledAndReportedAsATimeout() {
        long started = System.nanoTime();

        SandboxResult result = python("import time\ntime.sleep(3600)\n", new ExecutionLimits(1_000, 128));

        assertStoppedInTime(result, 1_000, Duration.ofNanos(System.nanoTime() - started));
    }

    @Test
    void aJavaInfiniteLoopIsKilledAndReportedAsATimeout() {
        long started = System.nanoTime();

        SandboxResult result = java("""
                public class Main {
                    public static void main(String[] args) {
                        while (true) {
                        }
                    }
                }
                """, new ExecutionLimits(1_000, 256));

        assertStoppedInTime(result, 1_000, Duration.ofNanos(System.nanoTime() - started));
    }

    // --- resource exhaustion: memory ---------------------------------------------------------------

    @Test
    void aMemoryBombIsKilledAndReportedAsMemoryLimit() {
        SandboxResult result =
                python("x = []\nwhile True:\n    x.append(bytearray(10_000_000))\n", new ExecutionLimits(10_000, 64));

        assertThat(result.status())
                .as(result.toString() + " stderr=<" + result.stderr().strip() + ">")
                .isEqualTo(SandboxStatus.MEMORY_LIMIT);
        assertThat(result.exitCode()).isEqualTo(137);
        assertThat(result.completedCleanly()).isFalse();
    }

    @Test
    void slowMemoryGrowthThatWouldSpillIntoSwapIsStillKilledAtTheLimit() {
        // 1 MB every 10 ms, all of it written to: with swap available the kernel would push the excess
        // out to disk and the program would live on until the timeout. With swap disabled the limit is hard.
        SandboxResult result = python("""
                import time
                hog = []
                while True:
                    hog.append(b'x' * 1_000_000)
                    time.sleep(0.01)
                """, new ExecutionLimits(20_000, 64));

        assertThat(result.status())
                .as(result.toString() + " stderr=<" + result.stderr().strip() + ">")
                .isEqualTo(SandboxStatus.MEMORY_LIMIT);
        assertThat(result.runtime()).as("killed by memory, not by the clock").isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void theContainersOwnControlGroupHasNoSwapAtAll() {
        SandboxResult result = probe("""
                def read(path):
                    try:
                        return open(path).read().strip()
                    except OSError:
                        return 'absent'
                print('memory.max=' + read('/sys/fs/cgroup/memory.max'))
                print('memory.swap.max=' + read('/sys/fs/cgroup/memory.swap.max'))
                """);
        Map<String, String> facts = facts(result);

        Assumptions.assumeTrue(
                !"absent".equals(facts.get("memory.swap.max")), "control group v1 or swap accounting off on this host");
        assertThat(facts.get("memory.max")).isEqualTo(String.valueOf(128L * 1024 * 1024));
        assertThat(facts.get("memory.swap.max")).isEqualTo("0");
    }

    // --- resource exhaustion: processes ------------------------------------------------------------

    @Test
    void aForkBombIsHeldAtTheProcessLimit() {
        // Each child sleeps, so every successful fork is one more live process. The parent stops at the
        // first refusal and reports how far it got: never beyond the limit.
        SandboxResult result = probe("""
                import time
                children = 0
                try:
                    while True:
                        if os.fork() == 0:
                            time.sleep(60)
                            os._exit(0)
                        children += 1
                except OSError as e:
                    print('error=' + errno.errorcode.get(e.errno, str(e.errno)))
                print('children=' + str(children))
                """);
        Map<String, String> facts = facts(result);

        assertThat(facts.get("error")).isEqualTo("EAGAIN");
        assertThat(Integer.parseInt(facts.get("children"))).isGreaterThan(20).isLessThan(DockerRunArguments.PIDS_LIMIT);
        assertSandboxStillGradesAnHonestProgram();
    }

    @Test
    void aForkBombThatKeepsTryingIsKilledByTheClockOrByMemory() {
        // Every process, children included, forks again and ignores the refusals. The clock (TIMEOUT) or the
        // memory limit (MEMORY_LIMIT, seen on native Linux Docker) ends it, whichever comes first.
        long started = System.nanoTime();

        SandboxResult result = python("""
                import os
                while True:
                    try:
                        os.fork()
                    except OSError:
                        pass
                """, new ExecutionLimits(1_000, 128));

        assertThat(result.status()).isIn(SandboxStatus.TIMEOUT, SandboxStatus.MEMORY_LIMIT);
        assertThat(result.completedCleanly()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofMillis(1_000)
                        .plus(DockerCliSandboxRunner.START_GRACE)
                        .plus(KILL_ALLOWANCE));
        assertSandboxStillGradesAnHonestProgram();
    }

    @Test
    void aForkBombThatDiesOnTheFirstRefusalIsStoppedByTheProcessOrMemoryLimit() {
        // Which limit bites first depends on the host: about 127 busy Python processes need more than
        // 128 MB on a native Linux Docker (CI: MEMORY_LIMIT), less on Docker Desktop (RUNTIME_ERROR from the
        // refused fork). Both are the sandbox doing its job; neither is a completed run.
        SandboxResult result = python("import os\nwhile True:\n    os.fork()\n", new ExecutionLimits(10_000, 128));

        assertThat(result.status()).isIn(SandboxStatus.RUNTIME_ERROR, SandboxStatus.MEMORY_LIMIT);
        assertThat(result.completedCleanly()).isFalse();
        if (result.status() == SandboxStatus.RUNTIME_ERROR) {
            assertThat(result.stderr()).contains("Resource temporarily unavailable");
        }
        assertSandboxStillGradesAnHonestProgram();
    }

    @Test
    void aJavaThreadBombIsHeldByTheSameProcessLimit() {
        // Threads count as processes for the limit; the JVM's own threads use part of it.
        SandboxResult result = java("""
                public class Main {
                    public static void main(String[] args) {
                        int started = 0;
                        try {
                            while (true) {
                                Thread t = new Thread(() -> {
                                    try {
                                        Thread.sleep(60_000);
                                    } catch (InterruptedException e) {
                                    }
                                });
                                t.setDaemon(true);
                                t.start();
                                started++;
                            }
                        } catch (Throwable e) {
                            System.out.println("error=" + e.getClass().getSimpleName());
                        }
                        System.out.println("threads=" + started);
                        System.exit(0);
                    }
                }
                """, new ExecutionLimits(20_000, 256));
        Map<String, String> facts = facts(result);

        assertThat(result.status()).as(result.stderr()).isEqualTo(SandboxStatus.OK);
        assertThat(facts.get("error")).isEqualTo("OutOfMemoryError");
        assertThat(Integer.parseInt(facts.get("threads"))).isGreaterThan(10).isLessThan(DockerRunArguments.PIDS_LIMIT);
    }

    // --- resource exhaustion: output and disk ------------------------------------------------------

    @Test
    void giantOutputIsCutAtTheCapAndNeverComparable() {
        SandboxResult result = python("import sys\nsys.stdout.write('0123456789' * 1000000)\n", PROBE);

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdoutTruncated()).isTrue();
        assertThat(result.stdout().length()).isEqualTo(DockerCliSandboxRunner.OUTPUT_LIMIT_BYTES);
        assertThat(result.completedCleanly()).isFalse();
    }

    @Test
    void outputWithoutEndIsBoundedAndStoppedByTheTimeout() {
        long started = System.nanoTime();

        SandboxResult result = python("while True:\n    print('x' * 100)\n", new ExecutionLimits(1_500, 128));

        assertStoppedInTime(result, 1_500, Duration.ofNanos(System.nanoTime() - started));
        assertThat(result.stdoutTruncated()).isTrue();
        assertThat(result.stdout().length()).isEqualTo(DockerCliSandboxRunner.OUTPUT_LIMIT_BYTES);
    }

    @Test
    void fillingTheTemporaryDirectoryStopsAtItsSize() {
        SandboxResult result = probe("""
                written = 0
                chunk = b'x' * 1048576
                try:
                    with open('/tmp/fill', 'wb') as f:
                        while True:
                            f.write(chunk)
                            f.flush()
                            written += 1
                except OSError as e:
                    print('error=' + errno.errorcode.get(e.errno, str(e.errno)))
                print('written_mb=' + str(written))
                """);
        Map<String, String> facts = facts(result);

        assertThat(facts.get("error")).isEqualTo("ENOSPC");
        assertThat(Integer.parseInt(facts.get("written_mb"))).isBetween(1, 64);
    }

    // --- filesystem manipulation -------------------------------------------------------------------

    @Test
    void nothingCanBeWrittenOutsideTheTemporaryDirectory() {
        SandboxResult result = probe("""
                attempt('write_root', lambda: write('/new.txt'))
                attempt('write_etc_passwd', lambda: write('/etc/passwd'))
                attempt('write_usr_bin', lambda: write('/usr/bin/evil'))
                attempt('write_dev', lambda: write('/dev/evil'))
                attempt('write_proc_sys', lambda: write('/proc/sys/kernel/hostname'))
                attempt('write_new_file_in_work', lambda: write('/work/new.txt'))
                attempt('overwrite_own_source', lambda: write('/work/main.py'))
                attempt('delete_own_source', lambda: os.remove('/work/main.py'))
                attempt('rename_own_source', lambda: os.rename('/work/main.py', '/work/other.py'))
                attempt('symlink_in_work', lambda: os.symlink('/etc/passwd', '/work/link'))
                attempt('chmod_passwd', lambda: os.chmod('/etc/passwd', 0o777))
                attempt('write_tmp', lambda: write('/tmp/ok.txt'))
                """);
        Map<String, String> facts = facts(result);

        assertRefused(
                facts,
                "write_root",
                "write_etc_passwd",
                "write_usr_bin",
                "write_dev",
                "write_proc_sys",
                "write_new_file_in_work",
                "overwrite_own_source",
                "delete_own_source",
                "rename_own_source",
                "symlink_in_work",
                "chmod_passwd");
        assertThat(facts.get("write_root")).isEqualTo("EROFS");
        assertThat(facts.get("write_new_file_in_work")).isEqualTo("EROFS");
        assertThat(facts.get("overwrite_own_source")).isEqualTo("EROFS");
        assertThat(facts).as("the one place a program may write").containsEntry("write_tmp", "ALLOWED");
    }

    @Test
    void aProgramWrittenToTheTemporaryDirectoryCannotBeExecuted() {
        SandboxResult result = probe("""
                import subprocess
                def run_from_tmp():
                    with open('/tmp/evil.sh', 'w') as f:
                        f.write('#!/bin/sh' + chr(10) + 'echo ran' + chr(10))
                    os.chmod('/tmp/evil.sh', 0o755)
                    subprocess.run(['/tmp/evil.sh'], check=True)
                attempt('exec_from_tmp', run_from_tmp)
                """);

        assertRefused(facts(result), "exec_from_tmp");
        assertThat(facts(result).get("exec_from_tmp")).isEqualTo("EACCES");
    }

    // --- sensitive information exposure ------------------------------------------------------------

    @Test
    void protectedFilesAndTheDockerSocketAreOutOfReach() {
        SandboxResult result = probe("""
                attempt('read_shadow', lambda: open('/etc/shadow').read())
                attempt('list_root_home', lambda: os.listdir('/root'))
                def kcore():
                    # Docker masks this file with an empty one: reading it may work, but yields nothing.
                    assert not open('/proc/kcore', 'rb').read(16), 'kernel memory was readable'
                attempt('read_kcore', kcore)
                attempt('docker_socket', lambda: os.stat('/var/run/docker.sock'))
                attempt('docker_socket_run', lambda: os.stat('/run/docker.sock'))
                for path in ['/host', '/host_mnt', '/mnt/host', '/mnt/c', '/run/desktop', '/var/lib/docker', '/Users', '/c']:
                    print('exists ' + path + '=' + str(os.path.exists(path)))
                """);
        Map<String, String> facts = facts(result);

        assertRefused(facts, "read_shadow", "list_root_home", "docker_socket", "docker_socket_run");
        assertThat(facts.get("read_kcore")).as("no kernel memory came back").isNotEqualTo("AssertionError");
        assertThat(facts.get("docker_socket")).isEqualTo("ENOENT");
        assertThat(facts.entrySet().stream().filter(e -> e.getKey().startsWith("exists ")))
                .as("host paths that exist inside the container")
                .allSatisfy(e -> assertThat(e.getValue()).as(e.getKey()).isEqualTo("False"));
    }

    @Test
    void aProgramSeesOnlyItsOwnDirectoryNotTheOtherSubmissionsOrTheHostsWorkRoot() throws IOException {
        Path neighbour = Files.createDirectory(workRoot.resolve("run-neighbour"));
        Files.writeString(neighbour.resolve("secret-answer.txt"), "NEIGHBOUR-MARKER");
        String stdin = workRoot.getFileName() + "\nrun-neighbour\nsecret-answer.txt\n";

        try {
            assertOnlyOwnDirectoryIsVisible(stdin);
        } finally {
            // Not a run directory of the runner: the leftover check would otherwise count it.
            Files.deleteIfExists(neighbour.resolve("secret-answer.txt"));
            Files.deleteIfExists(neighbour);
        }
    }

    private void assertOnlyOwnDirectoryIsVisible(String stdin) {
        SandboxResult result = probe("""
                names = {sys.stdin.readline().strip(), sys.stdin.readline().strip(), sys.stdin.readline().strip()}
                hits = []
                for base, dirs, files in os.walk('/'):
                    if base in ('/proc', '/sys', '/dev'):
                        dirs[:] = []
                        continue
                    for name in dirs + files:
                        if name in names:
                            hits.append(os.path.join(base, name))
                print('work=' + ','.join(sorted(os.listdir('/work'))))
                print('hits=' + str(len(hits)))
                """, stdin);
        Map<String, String> facts = facts(result);

        assertThat(facts.get("work")).isEqualTo("main.py");
        assertThat(facts.get("hits")).as(result.stdout()).isEqualTo("0");
    }

    @Test
    void theBackendsSecretsAreNotPassedIntoTheContainer() {
        SandboxResult result =
                probe("for key in sorted(os.environ):\n    print('env=' + key + '=' + os.environ[key])\n");
        String output = result.stdout();

        assertThat(output.lines()
                        .filter(line -> line.startsWith("env=") && line.indexOf('=', 4) > 0)
                        .map(line -> line.substring(4, line.indexOf('=', 4)).toUpperCase()))
                .as("environment variable names")
                .noneMatch(key -> Stream.of(
                                "SECRET",
                                "TOKEN",
                                "PASSWORD",
                                "PASSWD",
                                "JWT",
                                "SPRING",
                                "DATASOURCE",
                                "AI_SERVICE",
                                "GEMINI",
                                "DOCKER")
                        .anyMatch(key::contains));
        // And whatever secret this very process holds does not appear anywhere in the container's output.
        System.getenv().forEach((key, value) -> {
            if (key.matches("(?i).*(SECRET|TOKEN|PASSWORD).*") && value.length() >= 8) {
                assertThat(output).as("value of " + key).doesNotContain(value);
            }
        });
    }

    // --- external communication --------------------------------------------------------------------

    @Test
    void thereIsNoWayOutOverTheNetwork() {
        SandboxResult result = probe("""
                def udp():
                    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                    s.settimeout(2)
                    s.sendto(b'x', ('8.8.8.8', 53))
                attempt('tcp_public_ip', lambda: socket.create_connection(('1.1.1.1', 53), timeout=3))
                attempt('dns', lambda: socket.getaddrinfo('example.com', 80))
                attempt('udp_public_ip', udp)
                attempt('raw_socket', lambda: socket.socket(socket.AF_INET, socket.SOCK_RAW, socket.IPPROTO_ICMP))
                print('interfaces=' + ','.join(name for _, name in socket.if_nameindex()))
                """);
        Map<String, String> facts = facts(result);

        assertRefused(facts, "tcp_public_ip", "dns", "udp_public_ip", "raw_socket");
        assertThat(facts.get("raw_socket")).isEqualTo("EPERM");
        assertThat(facts.get("interfaces"))
                .as("only the container's own loopback")
                .isEqualTo("lo");
    }

    @Test
    void aServiceListeningOnTheHostCannotBeReached() throws IOException {
        try (ServerSocket hostService = new ServerSocket(0)) {
            SandboxResult result = probe("""
                    port = int(sys.stdin.readline())
                    for host in ['127.0.0.1', '172.17.0.1', 'host.docker.internal', 'gateway.docker.internal']:
                        attempt('connect ' + host, lambda host=host: socket.create_connection((host, port), timeout=2))
                    """, hostService.getLocalPort() + "\n");

            assertRefused(
                    facts(result),
                    "connect 127.0.0.1",
                    "connect 172.17.0.1",
                    "connect host.docker.internal",
                    "connect gateway.docker.internal");
            hostService.setSoTimeout(200);
            assertThatThrownBy(hostService::accept)
                    .as("nothing ever connected to the host's port")
                    .isInstanceOf(SocketTimeoutException.class);
        }
    }

    @Test
    void aJavaProgramCannotReachTheNetworkEither() {
        SandboxResult result = java("""
                import java.net.InetSocketAddress;
                import java.net.Socket;
                public class Main {
                    public static void main(String[] args) {
                        try (Socket s = new Socket()) {
                            s.connect(new InetSocketAddress("1.1.1.1", 53), 2000);
                            System.out.println("connected");
                        } catch (Exception e) {
                            System.out.println("blocked " + e.getClass().getSimpleName());
                        }
                    }
                }
                """, new ExecutionLimits(10_000, 256));

        assertThat(result.status()).as(result.stderr()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdout()).startsWith("blocked");
    }

    // --- dangerous operations ----------------------------------------------------------------------

    @Test
    void theProgramHasNoPrivilegeAndCannotGainAny() {
        SandboxResult result = probe("""
                status = {}
                for line in open('/proc/self/status').read().splitlines():
                    key, _, value = line.partition(':')
                    status[key] = value.strip()
                print('uid=' + status['Uid'].split()[0])
                print('CapEff=' + status['CapEff'])
                print('CapBnd=' + status['CapBnd'])
                print('NoNewPrivs=' + status['NoNewPrivs'])
                def mount():
                    import ctypes
                    libc = ctypes.CDLL(None, use_errno=True)
                    if libc.mount(b'none', b'/tmp', b'tmpfs', 0, None) != 0:
                        raise OSError(ctypes.get_errno(), 'mount')
                attempt('setuid_root', lambda: os.setuid(0))
                attempt('setgid_root', lambda: os.setgid(0))
                attempt('chroot', lambda: os.chroot('/tmp'))
                attempt('mount', mount)
                """);
        Map<String, String> facts = facts(result);

        assertThat(facts.get("uid")).isEqualTo("10001");
        assertThat(facts.get("CapEff")).as("effective capabilities").isEqualTo("0000000000000000");
        assertThat(facts.get("CapBnd")).as("bounding set").isEqualTo("0000000000000000");
        assertThat(facts.get("NoNewPrivs")).isEqualTo("1");
        assertRefused(facts, "setuid_root", "setgid_root", "chroot", "mount");
        assertThat(facts.get("setuid_root")).isEqualTo("EPERM");
        assertThat(facts.get("mount")).isEqualTo("EPERM");
    }
}
