/*
 * Copyright (C) 2010 The Android Open Source Project
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
package com.android.tradefed.device;

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.Client;
import com.android.ddmlib.FileListingService;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.InstallException;
import com.android.ddmlib.RawImage;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.SyncService;
import com.android.ddmlib.TimeoutException;
import com.android.ddmlib.log.LogReceiver;
import com.android.ddmlib.testrunner.IRemoteAndroidTestRunner;
import com.android.ddmlib.testrunner.ITestRunListener;
import com.android.tradefed.device.ITestDevice.RecoveryMode;
import com.android.tradefed.device.TestDevice.LogCatReceiver;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.StreamUtil;

import junit.framework.TestCase;

import org.easymock.EasyMock;
import org.easymock.IAnswer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

/**
 * Unit tests for {@link TestDevice}.
 */
public class TestDeviceTest extends TestCase {

    private IDevice mMockIDevice;
    private IShellOutputReceiver mMockReceiver;
    private TestDevice mTestDevice;
    private TestDevice mRecoveryTestDevice;
    private TestDevice mNoFastbootTestDevice;
    private IDeviceRecovery mMockRecovery;
    private IDeviceStateMonitor mMockMonitor;
    private IRunUtil mMockRunUtil;
    private IWifiHelper mMockWifi;

    /**
     * {@inheritDoc}
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mMockIDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockIDevice.getSerialNumber()).andReturn("serial").anyTimes();
        mMockReceiver = EasyMock.createMock(IShellOutputReceiver.class);
        mMockRecovery = EasyMock.createMock(IDeviceRecovery.class);
        mMockMonitor = EasyMock.createMock(IDeviceStateMonitor.class);
        mMockRunUtil = EasyMock.createMock(IRunUtil.class);
        mMockWifi = EasyMock.createMock(IWifiHelper.class);

        class TestableTestDevice extends TestDevice {
            public TestableTestDevice() {
                super(mMockIDevice, mMockMonitor);
            }

            @Override
            public void postBootSetup() {
                // too annoying to mock out postBootSetup actions everyone, so do nothing
            }

            @Override
            IRunUtil getRunUtil() {
                return mMockRunUtil;
            }

            @Override
            void doReboot() throws DeviceNotAvailableException, UnsupportedOperationException {
            }
        }

        // A TestDevice with a no-op recoverDevice() implementation
        mTestDevice = new TestableTestDevice() {
            @Override
            public void recoverDevice() throws DeviceNotAvailableException {
                // ignore
            }

            @Override
            IWifiHelper createWifiHelper() {
                return mMockWifi;
            }
        };
        mTestDevice.setRecovery(mMockRecovery);
        mTestDevice.setCommandTimeout(100);
        mTestDevice.setLogStartDelay(-1);

        // TestDevice with intact recoverDevice()
        mRecoveryTestDevice = new TestableTestDevice();
        mRecoveryTestDevice.setRecovery(mMockRecovery);
        mRecoveryTestDevice.setCommandTimeout(100);
        mRecoveryTestDevice.setLogStartDelay(-1);

        // TestDevice without fastboot
        mNoFastbootTestDevice = new TestableTestDevice();
        mNoFastbootTestDevice.setFastbootEnabled(false);
        mNoFastbootTestDevice.setRecovery(mMockRecovery);
        mNoFastbootTestDevice.setCommandTimeout(100);
        mNoFastbootTestDevice.setLogStartDelay(-1);
    }

    /**
     * Test {@link TestDevice#enableAdbRoot()} when adb is already root
     */
    public void testEnableAdbRoot_alreadyRoot() throws Exception {
        injectShellResponse("id", "uid=0(root) gid=0(root)");
        EasyMock.replay(mMockIDevice);
        assertTrue(mTestDevice.enableAdbRoot());
    }

    /**
     * Test {@link TestDevice#enableAdbRoot()} when adb is not root
     */
    public void testEnableAdbRoot_notRoot() throws Exception {
        injectShellResponse("id", "uid=2000(shell) gid=2000(shell)");
        CommandResult adbResult = new CommandResult();
        adbResult.setStatus(CommandStatus.SUCCESS);
        adbResult.setStdout("restarting adbd as root");
        EasyMock.expect(
                mMockRunUtil.runTimedCmd(EasyMock.anyLong(), EasyMock.eq("adb"),
                        EasyMock.eq("-s"), EasyMock.eq("serial"), EasyMock.eq("root"))).andReturn(
                adbResult);
        EasyMock.expect(mMockMonitor.waitForDeviceNotAvailable(EasyMock.anyLong())).andReturn(
                Boolean.TRUE);
        EasyMock.expect(mMockMonitor.waitForDeviceOnline()).andReturn(
                mMockIDevice);
        EasyMock.replay(mMockIDevice, mMockRunUtil, mMockMonitor);
        assertTrue(mTestDevice.enableAdbRoot());
    }

    /**
     * Test that {@link TestDevice#enableAdbRoot()} reattempts adb root on unexpected output
     */
    public void testEnableAdbRoot_rootRetry() throws Exception {
        injectShellResponse("id", "uid=2000(shell) gid=2000(shell)");
        CommandResult adbBadResult = new CommandResult(CommandStatus.SUCCESS);
        adbBadResult.setStdout("");
        EasyMock.expect(
                mMockRunUtil.runTimedCmd(EasyMock.anyLong(), EasyMock.eq("adb"),
                        EasyMock.eq("-s"), EasyMock.eq("serial"), EasyMock.eq("root"))).andReturn(
                adbBadResult);
        CommandResult adbResult = new CommandResult(CommandStatus.SUCCESS);
        adbResult.setStdout("restarting adbd as root");
        EasyMock.expect(
                mMockRunUtil.runTimedCmd(EasyMock.anyLong(), EasyMock.eq("adb"),
                        EasyMock.eq("-s"), EasyMock.eq("serial"), EasyMock.eq("root"))).andReturn(
                adbResult);
        EasyMock.expect(mMockMonitor.waitForDeviceNotAvailable(EasyMock.anyLong())).andReturn(
                Boolean.TRUE);
        EasyMock.expect(mMockMonitor.waitForDeviceOnline()).andReturn(
                mMockIDevice);
        EasyMock.replay(mMockIDevice, mMockRunUtil, mMockMonitor);
        assertTrue(mTestDevice.enableAdbRoot());
    }

    /**
     * Test {@link TestDevice#getProductType()} when device is in fastboot and IDevice has not
     * cached product type property
     */
    public void testGetProductType_fastboot() throws DeviceNotAvailableException {
        mMockIDevice.getProperty((String)EasyMock.anyObject());
        EasyMock.expectLastCall().andReturn((String)null);
        CommandResult fastbootResult = new CommandResult();
        fastbootResult.setStatus(CommandStatus.SUCCESS);
        // output of this cmd goes to stderr
        fastbootResult.setStdout("");
        fastbootResult.setStderr("product: nexusone\n" + "finished. total time: 0.001s");
        EasyMock.expect(
                mMockRunUtil.runTimedCmd(EasyMock.anyLong(), (String)EasyMock.anyObject(),
                        (String)EasyMock.anyObject(), (String)EasyMock.anyObject(),
                        (String)EasyMock.anyObject(), (String)EasyMock.anyObject())).andReturn(
                fastbootResult);
        EasyMock.replay(mMockIDevice, mMockRunUtil);
        mRecoveryTestDevice.setDeviceState(TestDeviceState.FASTBOOT);
        assertEquals("nexusone", mRecoveryTestDevice.getProductType());
    }

    /**
     * Test {@link TestDevice#getProductType()} for a device with a non-alphanumeric fastboot
     * product type
     */
    public void testGetProductType_fastbootNonalpha() throws DeviceNotAvailableException {
        mMockIDevice.getProperty((String)EasyMock.anyObject());
        EasyMock.expectLastCall().andReturn((String)null);
        CommandResult fastbootResult = new CommandResult();
        fastbootResult.setStatus(CommandStatus.SUCCESS);
        // output of this cmd goes to stderr
        fastbootResult.setStdout("");
        fastbootResult.setStderr("product: foo-bar\n" + "finished. total time: 0.001s");
        EasyMock.expect(
                mMockRunUtil.runTimedCmd(EasyMock.anyLong(), (String)EasyMock.anyObject(),
                        (String)EasyMock.anyObject(), (String)EasyMock.anyObject(),
                        (String)EasyMock.anyObject(), (String)EasyMock.anyObject())).andReturn(
                fastbootResult);
        EasyMock.replay(mMockIDevice, mMockRunUtil);
        mRecoveryTestDevice.setDeviceState(TestDeviceState.FASTBOOT);
        assertEquals("foo-bar", mRecoveryTestDevice.getProductType());
    }

    /**
     * Verify that {@link TestDevice#getProductType()} throws an exception if requesting a product
     * type directly fails while the device is in fastboot.
     */
    public void testGetProductType_fastbootFail() throws DeviceNotAvailableException {
        mMockIDevice.getProperty((String)EasyMock.anyObject());
        EasyMock.expectLastCall().andReturn((String)null).anyTimes();
        CommandResult fastbootResult = new CommandResult();
        fastbootResult.setStatus(CommandStatus.SUCCESS);
        // output of this cmd goes to stderr
        fastbootResult.setStdout("");
        fastbootResult.setStderr("product: \n" + "finished. total time: 0.001s");
        EasyMock.expect(
                mMockRunUtil.runTimedCmd(EasyMock.anyLong(), (String)EasyMock.anyObject(),
                        (String)EasyMock.anyObject(), (String)EasyMock.anyObject(),
                        (String)EasyMock.anyObject(), (String)EasyMock.anyObject())).andReturn(
                fastbootResult).anyTimes();
        EasyMock.replay(mMockIDevice);
        EasyMock.replay(mMockRunUtil);
        mTestDevice.setDeviceState(TestDeviceState.FASTBOOT);
        try {
            String type = mTestDevice.getProductType();
            fail(String.format("DeviceNotAvailableException not thrown; productType was '%s'",
                    type));
        } catch (DeviceNotAvailableException e) {
            // expected
        }
    }

    /**
     * Test {@link TestDevice#getProductType()} when device is in adb and IDevice has not cached
     * product type property
     */
    public void testGetProductType_adb() throws DeviceNotAvailableException, IOException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
        mMockIDevice.getProperty((String)EasyMock.anyObject());
        EasyMock.expectLastCall().andReturn((String)null);
        final String expectedOutput = "nexusone";
        mMockIDevice.executeShellCommand(EasyMock.eq("getprop ro.product.board"),
                (IShellOutputReceiver)EasyMock.anyObject(), EasyMock.anyInt());
        EasyMock.expectLastCall().andDelegateTo(new MockDevice() {
            @Override
            public void executeShellCommand(String cmd, IShellOutputReceiver receiver,
                    int timeout) {
                byte[] inputData = expectedOutput.getBytes();
                receiver.addOutput(inputData, 0, inputData.length);
            }
        });
        EasyMock.replay(mMockIDevice);
        assertEquals(expectedOutput, mTestDevice.getProductType());
    }

    /**
     * Verify that {@link TestDevice#getProductType()} throws an exception if requesting a product
     * type directly still fails.
     */
    public void testGetProductType_adbFail() throws DeviceNotAvailableException, IOException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
        mMockIDevice.getProperty((String)EasyMock.anyObject());
        EasyMock.expectLastCall().andReturn((String)null).anyTimes();
        // direct query fails: getprop ro.product.board --> ""
        final String expectedOutput = "";
        mMockIDevice.executeShellCommand(EasyMock.eq("getprop ro.product.board"),
                (IShellOutputReceiver)EasyMock.anyObject(), EasyMock.anyInt());
        EasyMock.expectLastCall().andDelegateTo(new MockDevice() {
            @Override
            public void executeShellCommand(String cmd, IShellOutputReceiver receiver,
                    int timeout) {
                byte[] inputData = expectedOutput.getBytes();
                receiver.addOutput(inputData, 0, inputData.length);
            }
        }).anyTimes();
        // last-ditch query fails: getprop ro.product.device --> ""
        mMockIDevice.executeShellCommand(EasyMock.eq("getprop ro.product.device"),
                (IShellOutputReceiver)EasyMock.anyObject(), EasyMock.anyInt());
        EasyMock.expectLastCall().andDelegateTo(new MockDevice() {
            @Override
            public void executeShellCommand(String cmd, IShellOutputReceiver receiver,
                    int timeout) {
                byte[] inputData = expectedOutput.getBytes();
                receiver.addOutput(inputData, 0, inputData.length);
            }
        }).anyTimes();
        EasyMock.replay(mMockIDevice);
        try {
            mTestDevice.getProductType();
            fail("DeviceNotAvailableException not thrown");
        } catch (DeviceNotAvailableException e) {
            // expected
        }
    }

    /**
     * Test {@link TestDevice#clearErrorDialogs()} when both a error and anr dialog are present.
     */
    public void testClearErrorDialogs() throws IOException, DeviceNotAvailableException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
        final String anrOutput = "debugging=false crashing=false null notResponding=true "
                + "com.android.server.am.AppNotRespondingDialog@4534aaa0 bad=false\n blah\n";
        final String crashOutput = "debugging=false crashing=true "
                + "com.android.server.am.AppErrorDialog@45388a60 notResponding=false null bad=false"
                + "blah \n";
        // construct a string with 2 error dialogs of each type to ensure proper detection
        final String fourErrors = anrOutput + anrOutput + crashOutput + crashOutput;
        mMockIDevice.executeShellCommand((String)EasyMock.anyObject(),
                (IShellOutputReceiver)EasyMock.anyObject(), EasyMock.anyInt());
        EasyMock.expectLastCall().andDelegateTo(new MockDevice() {
            @Override
            public void executeShellCommand(String cmd, IShellOutputReceiver receiver) {
                byte[] inputData = fourErrors.getBytes();
                receiver.addOutput(inputData, 0, inputData.length);
            }
        });

        mMockIDevice.executeShellCommand((String)EasyMock.anyObject(),
                (IShellOutputReceiver)EasyMock.anyObject());
        // expect 4 key events to be sent - one for each dialog
        // and expect another dialog query - but return nothing
        EasyMock.expectLastCall().times(5);

        EasyMock.replay(mMockIDevice);
        mTestDevice.clearErrorDialogs();
    }

    /**
     * Test the log file size limiting.
     */
    public void testLogCatReceiver() throws IOException, InterruptedException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {
        mTestDevice.setTmpLogcatSize(10);
        final String input = "this is the output of greater than 10 bytes.";
        final String input2 = "this is the second output of greater than 10 bytes.";
        final String input3 = "<10bytes";
        LogCatReceiver receiver = mTestDevice.createLogcatReceiver();
        final Object notifier = new Object();

        // mock the call to get system build id
        EasyMock.expect(mMockIDevice.getProperty((String)EasyMock.anyObject())).andStubReturn("1");

        try {
            // expect shell command to be called, with any receiver
            mMockIDevice.executeShellCommand((String)EasyMock.anyObject(), (IShellOutputReceiver)
                    EasyMock.anyObject(), EasyMock.eq(0));
            EasyMock.expectLastCall().andDelegateTo(
                  new MockDevice() {
                      @Override
                      public void executeShellCommand(String cmd, IShellOutputReceiver receiver,
                              int timeout) {
                          byte[] inputData = input.getBytes();
                          // add log data > maximum. This will trigger a log swap, where inputData
                          // will be moved to the backup log file
                          receiver.addOutput(inputData, 0, inputData.length);
                          // inject the second input data > maximum. This will trigger another log
                          // swap, that will discard inputData. the backup log file will have
                          // inputData2, and the current log file will be empty
                          byte[] inputData2 = input2.getBytes();
                          receiver.addOutput(inputData2, 0, inputData2.length);
                          // inject log data smaller than max log data - that will not trigger a
                          // log swap. The backup log file should contain inputData2, and the
                          // current should contain inputData3
                          byte[] inputData3 = input3.getBytes();
                          receiver.addOutput(inputData3, 0, inputData3.length);
                          synchronized (notifier) {
                              notifier.notify();
                              try {
                                // block until interrupted
                                notifier.wait();
                              } catch (InterruptedException e) {
                            }
                          }
                      }
                  });
            EasyMock.replay(mMockIDevice);
            receiver.start();
            synchronized (notifier) {
                notifier.wait();
            }
            String actualString = StreamUtil.getStringFromStream(
                    receiver.getLogcatData().createInputStream());
            // verify that data from both the backup log file (input2) and current log file
            // (input3) is retrieved
            assertFalse(actualString.contains(input));
            assertTrue(actualString.contains(input2));
            assertTrue(actualString.contains(input3));
        } finally {
            receiver.cancel();
        }
    }

    public void testGetBugreport_deviceUnavail() throws Exception {
        final String testCommand = "bugreport";
        final String expectedOutput = "this is the output\r\n in two lines\r\n";
        // FIXME: this isn't actually causing a DeviceNotAvailableException to be thrown

        // expect shell command to be called, with any receiver
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), (IShellOutputReceiver)
                EasyMock.anyObject(), EasyMock.anyInt());
        EasyMock.expectLastCall().andDelegateTo(
                new MockDevice() {
                    @Override
                    public void executeShellCommand(String cmd, IShellOutputReceiver receiver,
                            int timeout) throws ShellCommandUnresponsiveException {
                        byte[] inputData = expectedOutput.getBytes();
                        receiver.addOutput(inputData, 0, inputData.length);
                        // device goes away
                        throw new ShellCommandUnresponsiveException();
                    }
                });
        mMockRecovery.recoverDevice(EasyMock.eq(mMockMonitor), EasyMock.eq(false));
        EasyMock.expectLastCall().andThrow(new DeviceNotAvailableException());

        EasyMock.replay(mMockIDevice);
        EasyMock.replay(mMockRecovery);
        assertEquals(expectedOutput, StreamUtil.getStringFromStream(
                mTestDevice.getBugreport().createInputStream()));
    }

    /**
     * Simple normal case test for
     * {@link TestDevice#executeShellCommand(String, IShellOutputReceiver)}.
     * <p/>
     * Verify that the shell command is routed to the IDevice.
     */
    public void testExecuteShellCommand_receiver() throws IOException, DeviceNotAvailableException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
        final String testCommand = "simple command";
        // expect shell command to be called
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), EasyMock.eq(mMockReceiver),
                EasyMock.anyInt());
        EasyMock.replay(mMockIDevice);
        mTestDevice.executeShellCommand(testCommand, mMockReceiver);
    }

    /**
     * Simple normal case test for
     * {@link TestDevice#executeShellCommand(String)}.
     * <p/>
     * Verify that the shell command is routed to the IDevice, and shell output is collected.
     * @throws ShellCommandUnresponsiveException
     * @throws AdbCommandRejectedException
     * @throws TimeoutException
     */
    public void testExecuteShellCommand() throws IOException, DeviceNotAvailableException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
        final String testCommand = "simple command";
        final String expectedOutput = "this is the output\r\n in two lines\r\n";

        // expect shell command to be called, with any receiver
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), (IShellOutputReceiver)
                EasyMock.anyObject(), EasyMock.anyInt());
        EasyMock.expectLastCall().andDelegateTo(
              new MockDevice() {
                  @Override
                  public void executeShellCommand(String cmd, IShellOutputReceiver receiver,
                          int timeout) {
                      byte[] inputData = expectedOutput.getBytes();
                      receiver.addOutput(inputData, 0, inputData.length);
                  }
              });
        EasyMock.replay(mMockIDevice);
        assertEquals(expectedOutput, mTestDevice.executeShellCommand(testCommand));
    }

    /**
     * Test {@link TestDevice#executeShellCommand(String, IShellOutputReceiver)} behavior when
     * {@link IDevice} throws IOException and recovery immediately fails.
     * <p/>
     * Verify that a DeviceNotAvailableException is thrown.
     */
    public void testExecuteShellCommand_recoveryFail() throws Exception {
        final String testCommand = "simple command";
        // expect shell command to be called
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), EasyMock.eq(mMockReceiver),
                EasyMock.anyInt());
        EasyMock.expectLastCall().andThrow(new IOException());
        mMockRecovery.recoverDevice(EasyMock.eq(mMockMonitor), EasyMock.eq(false));
        EasyMock.expectLastCall().andThrow(new DeviceNotAvailableException());
        EasyMock.replay(mMockIDevice);
        EasyMock.replay(mMockRecovery);
        try {
            mRecoveryTestDevice.executeShellCommand(testCommand, mMockReceiver);
            fail("DeviceNotAvailableException not thrown");
        } catch (DeviceNotAvailableException e) {
            // expected
        }
    }

    /**
     * Test {@link TestDevice#executeShellCommand(String, IShellOutputReceiver)} behavior when
     * {@link IDevice} throws IOException and device is in recovery until online mode.
     * <p/>
     * Verify that a DeviceNotAvailableException is thrown.
     */
    public void testExecuteShellCommand_recoveryUntilOnline() throws Exception {
        final String testCommand = "simple command";
        // expect shell command to be called
        mRecoveryTestDevice.setRecoveryMode(RecoveryMode.ONLINE);
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), EasyMock.eq(mMockReceiver),
                EasyMock.anyInt());
        EasyMock.expectLastCall().andThrow(new IOException());
        mMockRecovery.recoverDevice(EasyMock.eq(mMockMonitor), EasyMock.eq(true));
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), EasyMock.eq(mMockReceiver),
                EasyMock.anyInt());
        EasyMock.replay(mMockIDevice);
        EasyMock.replay(mMockRecovery);
        mRecoveryTestDevice.executeShellCommand(testCommand, mMockReceiver);
    }

    /**
     * Test {@link TestDevice#executeShellCommand(String, IShellOutputReceiver)} behavior when
     * {@link IDevice} throws IOException and recovery succeeds.
     * <p/>
     * Verify that command is re-tried.
     */
    public void testExecuteShellCommand_recoveryRetry() throws Exception {
        final String testCommand = "simple command";
        // expect shell command to be called
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), EasyMock.eq(mMockReceiver),
                EasyMock.anyInt());
        EasyMock.expectLastCall().andThrow(new IOException());
        assertRecoverySuccess();
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), EasyMock.eq(mMockReceiver),
                EasyMock.anyInt());
        replayMocks();
        mTestDevice.executeShellCommand(testCommand, mMockReceiver);
    }

    /** Set expectations for a successful recovery operation
     */
    private void assertRecoverySuccess() throws DeviceNotAvailableException, IOException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
        mMockRecovery.recoverDevice(EasyMock.eq(mMockMonitor), EasyMock.eq(false));
        // expect post boot up steps
        mMockIDevice.executeShellCommand(EasyMock.eq(mTestDevice.getDisableKeyguardCmd()),
                (IShellOutputReceiver)EasyMock.anyObject(), EasyMock.anyInt());
    }

    /**
     * Test {@link TestDevice#executeShellCommand(String, IShellOutputReceiver)} behavior when
     * command times out and recovery succeeds.
     * <p/>
     * Verify that command is re-tried.
     */
    public void testExecuteShellCommand_recoveryTimeoutRetry() throws Exception {
        final String testCommand = "simple command";
        // expect shell command to be called - and never return from that call
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), EasyMock.eq(mMockReceiver),
                EasyMock.anyInt());
        EasyMock.expectLastCall().andThrow(new TimeoutException());
        assertRecoverySuccess();
        // now expect shellCommand to be executed again, and succeed
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), EasyMock.eq(mMockReceiver),
                EasyMock.anyInt());
        replayMocks();
        mTestDevice.executeShellCommand(testCommand, mMockReceiver);
    }

    /**
     * Test {@link TestDevice#executeShellCommand(String, IShellOutputReceiver)} behavior when
     * {@link IDevice} repeatedly throws IOException and recovery succeeds.
     * <p/>
     * Verify that DeviceNotAvailableException is thrown.
     */
    public void testExecuteShellCommand_recoveryAttempts() throws Exception {
        final String testCommand = "simple command";
        // expect shell command to be called
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), EasyMock.eq(mMockReceiver),
                EasyMock.anyInt());
        EasyMock.expectLastCall().andThrow(new IOException()).times(
                TestDevice.MAX_RETRY_ATTEMPTS+1);
        for (int i=0; i <= TestDevice.MAX_RETRY_ATTEMPTS; i++) {
            assertRecoverySuccess();
        }
        replayMocks();
        try {
            mTestDevice.executeShellCommand(testCommand, mMockReceiver);
            fail("DeviceUnresponsiveException not thrown");
        } catch (DeviceUnresponsiveException e) {
            // expected
        }
    }

    /**
     * Puts all the mock objects into replay mode
     */
    private void replayMocks() {
        EasyMock.replay(mMockIDevice, mMockRecovery, mMockMonitor, mMockRunUtil, mMockWifi);
    }

    /**
     * Verify all the mock objects
     */
    private void verifyMocks() {
        EasyMock.verify(mMockIDevice, mMockRecovery, mMockMonitor, mMockRunUtil, mMockWifi);
    }


    /**
     * Unit test for {@link TestDevice#getExternalStoreFreeSpace()}.
     * <p/>
     * Verify that output of 'adb shell df' command is parsed correctly.
     */
    public void testGetExternalStoreFreeSpace() throws Exception {
        final String dfOutput =
            "/mnt/sdcard: 3864064K total, 1282880K used, 2581184K available (block size 32768)";
        assertGetExternalStoreFreeSpace(dfOutput, 2581184);
    }

    /**
     * Unit test for {@link TestDevice#getExternalStoreFreeSpace()}.
     * <p/>
     * Verify that the table-based output of 'adb shell df' command is parsed correctly.
     */
    public void testGetExternalStoreFreeSpace_table() throws Exception {
        final String dfOutput =
            "Filesystem             Size   Used   Free   Blksize\n" +
            "/mnt/sdcard              3G   787M     2G   4096";
        assertGetExternalStoreFreeSpace(dfOutput, 2 * 1024 * 1024);
    }

    /**
     * Unit test for {@link TestDevice#getExternalStoreFreeSpace()}.
     * <p/>
     * Verify behavior when 'df' command returns unexpected content
     */
    public void testGetExternalStoreFreeSpace_badOutput() throws Exception {
        final String dfOutput =
            "/mnt/sdcard: blaH";
        assertGetExternalStoreFreeSpace(dfOutput, 0);
    }

    /**
     * Helper method to verify the {@link TestDevice#getExternalStoreFreeSpace()} method under
     * different conditions.
     *
     * @param dfOutput the test output to inject
     * @param expectedFreeSpaceKB the expected free space
     */
    private void assertGetExternalStoreFreeSpace(final String dfOutput, long expectedFreeSpaceKB)
            throws Exception {
        final String mntPoint = "/mnt/sdcard";
        final String expectedCmd = "df " + mntPoint;
        EasyMock.expect(mMockMonitor.getMountPoint(IDevice.MNT_EXTERNAL_STORAGE)).andReturn(
                mntPoint);
        // expect shell command to be called, and return the test df output
        mMockIDevice.executeShellCommand(EasyMock.eq(expectedCmd),
                (IShellOutputReceiver)EasyMock.anyObject(), EasyMock.anyInt());
        EasyMock.expectLastCall().andDelegateTo(new MockDevice() {
            @Override
            public void executeShellCommand(String cmd, IShellOutputReceiver receiver, int timeout) {
                byte[] inputData = dfOutput.getBytes();
                receiver.addOutput(inputData, 0, inputData.length);
            }
        });
        EasyMock.replay(mMockIDevice, mMockMonitor);
        assertEquals(expectedFreeSpaceKB, mTestDevice.getExternalStoreFreeSpace());
    }

    /**
     * Unit test for {@link TestDevice#syncFiles)}.
     * <p/>
     * Verify behavior when given local file does not exist
     */
    public void testSyncFiles_missingLocal() throws Exception {
        EasyMock.replay(mMockIDevice);
        assertFalse(mTestDevice.syncFiles(new File("idontexist"), "/sdcard"));
    }

    /**
     * Test {@link TestDevice#runInstrumentationTests(IRemoteAndroidTestRunner, Collection)}
     * success case.
     */
    public void testRunInstrumentationTests() throws Exception {
        IRemoteAndroidTestRunner mockRunner = EasyMock.createMock(IRemoteAndroidTestRunner.class);
        EasyMock.expect(mockRunner.getPackageName()).andStubReturn("com.example");
        Collection<ITestRunListener> listeners = new ArrayList<ITestRunListener>(0);
        mockRunner.setMaxtimeToOutputResponse(EasyMock.anyInt());
        // expect runner.run command to be called
        mockRunner.run(listeners);
        EasyMock.replay(mockRunner);
        mTestDevice.runInstrumentationTests(mockRunner, listeners);
    }

    /**
     * Test {@link TestDevice#runInstrumentationTests(IRemoteAndroidTestRunner, Collection)}
     * when recovery fails.
     */
    public void testRunInstrumentationTests_recoveryFails() throws Exception {
        IRemoteAndroidTestRunner mockRunner = EasyMock.createMock(IRemoteAndroidTestRunner.class);
        Collection<ITestRunListener> listeners = new ArrayList<ITestRunListener>(1);
        ITestRunListener listener = EasyMock.createMock(ITestRunListener.class);
        listeners.add(listener);
        mockRunner.setMaxtimeToOutputResponse(EasyMock.anyInt());
        mockRunner.run(listeners);
        EasyMock.expectLastCall().andThrow(new IOException());
        EasyMock.expect(mockRunner.getPackageName()).andReturn("foo");
        listener.testRunFailed((String)EasyMock.anyObject());
        mMockRecovery.recoverDevice(EasyMock.eq(mMockMonitor), EasyMock.eq(false));
        EasyMock.expectLastCall().andThrow(new DeviceNotAvailableException());
        EasyMock.replay(listener, mockRunner, mMockIDevice, mMockRecovery);
        try {
            mRecoveryTestDevice.runInstrumentationTests(mockRunner, listeners);
            fail("DeviceNotAvailableException not thrown");
        } catch (DeviceNotAvailableException e) {
            // expected
        }
    }

    /**
     * Test {@link TestDevice#runInstrumentationTests(IRemoteAndroidTestRunner, Collection)}
     * when recovery succeeds.
     */
    public void testRunInstrumentationTests_recoverySucceeds() throws Exception {
        IRemoteAndroidTestRunner mockRunner = EasyMock.createMock(IRemoteAndroidTestRunner.class);
        Collection<ITestRunListener> listeners = new ArrayList<ITestRunListener>(1);
        ITestRunListener listener = EasyMock.createMock(ITestRunListener.class);
        listeners.add(listener);
        mockRunner.setMaxtimeToOutputResponse(EasyMock.anyInt());
        mockRunner.run(listeners);
        EasyMock.expectLastCall().andThrow(new IOException());
        EasyMock.expect(mockRunner.getPackageName()).andReturn("foo");
        listener.testRunFailed((String)EasyMock.anyObject());
        assertRecoverySuccess();
        EasyMock.replay(listener, mockRunner, mMockIDevice, mMockRecovery);
        mTestDevice.runInstrumentationTests(mockRunner, listeners);
    }

    /**
     * Test {@link TestDevice#executeFastbootCommand(String...)} throws an exception when fastboot
     * is not available.
     */
    public void testExecuteFastbootCommand_nofastboot() throws Exception {
        try {
            mNoFastbootTestDevice.executeFastbootCommand("");
            fail("UnsupportedOperationException not thrown");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    /**
     * Test {@link TestDevice#executeLongFastbootCommand(String...)} throws an exception when
     * fastboot is not available.
     */
    public void testExecuteLongFastbootCommand_nofastboot() throws Exception {
        try {
            mNoFastbootTestDevice.executeFastbootCommand("");
            fail("UnsupportedOperationException not thrown");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    /**
     * Test that state changes are ignore while {@link TestDevice#executeFastbootCommand(String...)}
     * is active.
     */
    public void testExecuteFastbootCommand_state() {
        // TODO: implement this
    }

    /**
     * Test recovery mode is entered when fastboot command fails
     */
    public void testExecuteFastbootCommand_recovery() throws UnsupportedOperationException,
           DeviceNotAvailableException {
        CommandResult result = new CommandResult(CommandStatus.EXCEPTION);
        EasyMock.expect(mMockRunUtil.runTimedCmd(EasyMock.anyLong(), EasyMock.eq("fastboot"),
                EasyMock.eq("-s"),EasyMock.eq("serial"), EasyMock.eq("foo"))).andReturn(result);
        mMockRecovery.recoverDeviceBootloader((IDeviceStateMonitor)EasyMock.anyObject());
        CommandResult successResult = new CommandResult(CommandStatus.SUCCESS);
        successResult.setStderr("");
        successResult.setStdout("");
        // now expect a successful retry
        EasyMock.expect(mMockRunUtil.runTimedCmd(EasyMock.anyLong(), EasyMock.eq("fastboot"),
                EasyMock.eq("-s"),EasyMock.eq("serial"), EasyMock.eq("foo"))).andReturn(
                        successResult);
        replayMocks();
        mTestDevice.executeFastbootCommand("foo");
        verifyMocks();

    }

    /**
     * Basic test for encryption if encryption is not supported.
     * <p>
     * Calls {@link TestDevice#encryptDevice(boolean)}, {@link TestDevice#unlockDevice()}, and
     * {@link TestDevice#unencryptDevice()} and makes sure that a
     * {@link UnsupportedOperationException} is thrown for each method.
     * </p>
     * @throws DeviceNotAvailableException
     * @throws IOException
     * @throws ShellCommandUnresponsiveException
     * @throws AdbCommandRejectedException
     * @throws TimeoutException
     */
    public void testEncryptionUnsupported() throws DeviceNotAvailableException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException, IOException {
        final String expectedCmd = "vdc cryptfs enablecrypto";
        final String output = "\r\n";
        mMockIDevice.executeShellCommand(EasyMock.eq(expectedCmd),
                (IShellOutputReceiver) EasyMock.anyObject(), EasyMock.anyInt());
        EasyMock.expectLastCall().andDelegateTo(new MockDevice() {
            @Override
            public void executeShellCommand(String cmd, IShellOutputReceiver receiver,
                    int timeout) {
                byte[] inputData = output.getBytes();
                receiver.addOutput(inputData, 0, inputData.length);
            }
        });
        EasyMock.replay(mMockIDevice);
        try {
            mTestDevice.encryptDevice(false);
            fail("encryptUserData() did not throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
        try {
            mTestDevice.unlockDevice();
            fail("decryptUserData() did not throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
        try {
            mTestDevice.unencryptDevice();
            fail("unencryptUserData() did not throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
        return;
    }

    /**
     * Simple test for {@link TestDevice#switchToAdbUsb()}
     */
    public void testSwitchToAdbUsb() throws Exception  {
        EasyMock.expect(mMockRunUtil.runTimedCmd(EasyMock.anyLong(), EasyMock.eq("adb"),
                EasyMock.eq("-s"), EasyMock.eq("serial"), EasyMock.eq("usb"))).andReturn(
                        new CommandResult(CommandStatus.SUCCESS));
        replayMocks();
        mTestDevice.switchToAdbUsb();
        verifyMocks();
    }

    /**
     * Test for {@link TestDevice#switchToAdbTcp()} when device has no ip address
     */
    public void testSwitchToAdbTcp_noIp() throws Exception  {
        EasyMock.expect(mMockWifi.getIpAddress(null)).andReturn(null);
        replayMocks();
        assertNull(mTestDevice.switchToAdbTcp());
        verifyMocks();
    }

    /**
     * Test normal success case for {@link TestDevice#switchToAdbTcp()}.
     */
    public void testSwitchToAdbTcp() throws Exception  {
        EasyMock.expect(mMockWifi.getIpAddress(null)).andReturn("ip");
        EasyMock.expect(mMockRunUtil.runTimedCmd(EasyMock.anyLong(), EasyMock.eq("adb"),
                EasyMock.eq("-s"), EasyMock.eq("serial"), EasyMock.eq("tcpip"),
                EasyMock.eq("5555"))).andReturn(
                        new CommandResult(CommandStatus.SUCCESS));
        replayMocks();
        assertEquals("ip:5555", mTestDevice.switchToAdbTcp());
        verifyMocks();
    }

    /**
     * Helper method to build a response to a executeShellCommand call
     *
     * @param expectedCommand the shell command to expect
     * @param response the response to simulate
     */
    @SuppressWarnings("unchecked")
    private void injectShellResponse(final String expectedCommand, final String response)
            throws Exception {
        IAnswer shellAnswer = new IAnswer() {
            @Override
            public Object answer() throws Throwable {
                IShellOutputReceiver receiver =
                    (IShellOutputReceiver)EasyMock.getCurrentArguments()[1];
                byte[] inputData = response.getBytes();
                receiver.addOutput(inputData, 0, inputData.length);
                return null;
            }
        };
        mMockIDevice.executeShellCommand(EasyMock.eq(expectedCommand),
                (IShellOutputReceiver)EasyMock.anyObject(),
                EasyMock.anyInt());
        EasyMock.expectLastCall().andAnswer(shellAnswer);
    }

    /**
     * Test normal success case for {@link TestDevice#reboot()}
     */
    public void testReboot() throws DeviceNotAvailableException {
        EasyMock.expect(mMockMonitor.waitForDeviceAvailable(EasyMock.anyLong())).andReturn(
                mMockIDevice);
        replayMocks();
        mTestDevice.reboot();
        verifyMocks();
    }

    /**
     * Test {@link TestDevice#reboot()} attempts a recovery upon failure
     */
    public void testRebootRecovers() throws DeviceNotAvailableException {
        EasyMock.expect(mMockMonitor.waitForDeviceOnline()).andReturn(mMockIDevice);
        EasyMock.expect(mMockMonitor.waitForDeviceAvailable(EasyMock.anyLong())).andReturn(null);
        mMockRecovery.recoverDevice(mMockMonitor, false);
        replayMocks();
        mRecoveryTestDevice.reboot();
        verifyMocks();
    }

    /**
     * Concrete mock implementation of {@link IDevice}.
     * <p/>
     * Needed in order to handle the EasyMock andDelegateTo operation.
     */
    private static class MockDevice implements IDevice {

        @Override
        public void createForward(int localPort, int remotePort) {
        }

        @Override
        public void executeShellCommand(String command, IShellOutputReceiver receiver)
                throws IOException, ShellCommandUnresponsiveException {
        }

        @Override
        public void executeShellCommand(String command, IShellOutputReceiver receiver, int timeout)
                throws TimeoutException, IOException, ShellCommandUnresponsiveException {
        }

        @Override
        public String getAvdName() {
            return null;
        }

        @Override
        public Client getClient(String applicationName) {
            return null;
        }

        @Override
        public String getClientName(int pid) {
            return null;
        }

        @Override
        public Client[] getClients() {
            return null;
        }

        @Override
        public FileListingService getFileListingService() {
            return null;
        }

        @Override
        public Map<String, String> getProperties() {
            return null;
        }

        @Override
        public String getProperty(String name) {
            return null;
        }

        @Override
        public int getPropertyCount() {
            return 0;
        }

        @Override
        public String getMountPoint(String name) {
            return null;
        }

        @Override
        public RawImage getScreenshot() throws IOException {
            return null;
        }

        @Override
        public String getSerialNumber() {
            return null;
        }

        @Override
        public DeviceState getState() {
            return null;
        }

        @Override
        public SyncService getSyncService() throws IOException {
            return null;
        }

        @Override
        public boolean hasClients() {
            return false;
        }

        @Override
        public String installPackage(String packageFilePath, boolean reinstall)
                throws InstallException {
            return null;
        }

        @Override
        public String installRemotePackage(String remoteFilePath, boolean reinstall)
                throws InstallException {
            return null;
        }

        @Override
        public boolean isBootLoader() {
            return false;
        }

        @Override
        public boolean isEmulator() {
            return false;
        }

        @Override
        public boolean isOffline() {
            return false;
        }

        @Override
        public boolean isOnline() {
            return false;
        }

        @Override
        public void removeForward(int localPort, int remotePort) {
        }

        @Override
        public void removeRemotePackage(String remoteFilePath) throws InstallException {
        }

        @Override
        public void runEventLogService(LogReceiver receiver) throws IOException {
        }

        @Override
        public void runLogService(String logname, LogReceiver receiver) throws IOException {
        }

        @Override
        public String syncPackageToDevice(String localFilePath) throws IOException {
            return null;
        }

        @Override
        public String uninstallPackage(String packageName) throws InstallException {
            return null;
        }

        @Override
        public void reboot(String into) throws IOException {
        }
    }
}

