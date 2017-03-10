/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tradefed.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.tradefed.command.CommandRunner.ExitCode;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.DeviceUnresponsiveException;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.IRescheduler;
import com.android.tradefed.invoker.ITestInvocation;
import com.android.tradefed.invoker.TestInvocation;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.util.FileUtil;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintWriter;

/**
 * Unit tests for {@link CommandRunner}. Always attempt to run on null-device (-n) to avoid hanging
 * if no physical device are available.
 */
@RunWith(JUnit4.class)
public class CommandRunnerTest {

    private static final String FAKE_CONFIG = "doesnotexit";
    private Throwable mThrowable = null;
    private String mStackTraceOutput = null;

    private static ICommandScheduler sOriginalScheduler = null;

    private static final String EMPTY_CONF_CONTENT =
            "<configuration description=\"Empty Config\" />";
    private File mConfig;
    private File mLogDir;

    @BeforeClass
    public static void beforeClass() throws Exception {
        // We have some global state that cannot be re-entered so we ensure they do not throw.
        try {
            GlobalConfiguration.createGlobalConfiguration(new String[] {});
        } catch (IllegalStateException e) {
            // ignore re-init.
        }
        sOriginalScheduler = GlobalConfiguration.getInstance().getCommandScheduler();
    }

    @AfterClass
    public static void afterClass() {
        GlobalConfiguration.getInstance().setCommandScheduler(sOriginalScheduler);
    }

    @Before
    public void setUp() throws Exception {
        mThrowable = null;
        mStackTraceOutput = null;
        mConfig = FileUtil.createTempFile("empty", ".xml");
        FileUtil.writeToFile(EMPTY_CONF_CONTENT, mConfig);
        mLogDir = FileUtil.createTempDir("command-runner-unit-test");
    }

    @After
    public void tearDown() {
        GlobalConfiguration.getInstance()
                .getCommandScheduler()
                .setLastInvocationExitCode(ExitCode.NO_ERROR, null);
        FileUtil.deleteFile(mConfig);
        FileUtil.recursiveDelete(mLogDir);
    }

    private class TestableCommandRunner extends CommandRunner {
        private Throwable mThrow;

        public TestableCommandRunner(Throwable t) {
            mThrow = t;
        }

        @Override
        public void initGlobalConfig(String[] args) throws ConfigurationException {
            GlobalConfiguration.getInstance()
                    .setCommandScheduler(
                            new CommandScheduler() {
                                @Override
                                void initDeviceManager() {
                                    try {
                                        super.initDeviceManager();
                                    } catch (IllegalStateException e) {
                                        // ignore re-init
                                    }
                                }

                                @Override
                                ITestInvocation createRunInstance() {
                                    if (mThrow == null) {
                                        return super.createRunInstance();
                                    }
                                    return new TestInvocation() {
                                        @Override
                                        public void invoke(
                                                IInvocationContext context,
                                                IConfiguration config,
                                                IRescheduler rescheduler,
                                                ITestInvocationListener... extraListeners)
                                                throws DeviceNotAvailableException, Throwable {
                                            throw mThrow;
                                        }
                                    };
                                }

                                // Prevent the logging dumping extra logs.
                                @Override
                                protected void initLogging() {}

                                @Override
                                protected void cleanUp() {}
                            });
        }

        /** We capture the stack trace if any. */
        @Override
        void printStackTrace(Throwable e) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintWriter pw = new PrintWriter(out);
            e.printStackTrace(pw);
            pw.flush();
            mStackTraceOutput = out.toString();
        }
    }

    /**
     * Run with a known empty config, should always pass.
     */
    @Test
    public void testRun_noError() throws Exception {
        mThrowable = null;
        mStackTraceOutput = null;
        CommandRunner mRunner = new TestableCommandRunner(null);
        String[] args = {
            mConfig.getAbsolutePath(),
            "-n",
            "--no-return-null",
            "--no-throw-build-error",
            "--log-file-path",
            mLogDir.getAbsolutePath()
        };
        mRunner.run(args);
        assertEquals(0, mRunner.getErrorCode().getCodeValue());
        assertNull(mStackTraceOutput);
    }

    /**
     * Run with a known empty config but fake a device unresponsive.
     */
    @Test
    public void testRun_deviceUnresponsive() {
        mThrowable = new DeviceUnresponsiveException("injected", "serial");
        CommandRunner mRunner = new TestableCommandRunner(mThrowable);
        String[] args = {
            mConfig.getAbsolutePath(), "-n", "--log-file-path", mLogDir.getAbsolutePath()
        };
        mRunner.run(args);
        assertEquals(ExitCode.DEVICE_UNRESPONSIVE, mRunner.getErrorCode());
        assertTrue(mStackTraceOutput
                .contains("com.android.tradefed.device.DeviceUnresponsiveException: injected"));
    }

    /**
     * Run with a known empty config but fake a device unavailable.
     */
    @Test
    public void testRun_deviceUnavailable() {
        mThrowable = new DeviceNotAvailableException("injected", "serial");
        CommandRunner mRunner = new TestableCommandRunner(mThrowable);
        String[] args = {
            mConfig.getAbsolutePath(), "-n", "--log-file-path", mLogDir.getAbsolutePath()
        };
        mRunner.run(args);
        assertEquals(ExitCode.DEVICE_UNAVAILABLE, mRunner.getErrorCode());
        assertTrue(mStackTraceOutput
                .contains("com.android.tradefed.device.DeviceNotAvailableException: injected"));
    }

    /**
     * Run with a known empty config but a throwable exception is caught.
     */
    @Test
    public void testRun_throwable() {
        mThrowable = new RuntimeException("injecting runtime");
        CommandRunner mRunner = new TestableCommandRunner(mThrowable);
        String[] args = {
            mConfig.getAbsolutePath(), "-n", "--log-file-path", mLogDir.getAbsolutePath()
        };
        mRunner.run(args);
        assertEquals(ExitCode.THROWABLE_EXCEPTION, mRunner.getErrorCode());
        assertTrue(String.format("%s does not contains the expected output", mStackTraceOutput),
                mStackTraceOutput.contains("java.lang.RuntimeException: injecting runtime"));
    }

    /**
     * Run with a non existant config and expect a configuration exception because of it.
     */
    @Test
    public void testRun_ConfigError() {
        String[] args = {FAKE_CONFIG};
        CommandRunner mRunner = new TestableCommandRunner(null);
        mRunner.run(args);
        assertEquals(ExitCode.CONFIG_EXCEPTION, mRunner.getErrorCode());
        assertTrue(
                "Stack does not contain expected message: " + mStackTraceOutput,
                mStackTraceOutput.contains(FAKE_CONFIG));
    }
}
