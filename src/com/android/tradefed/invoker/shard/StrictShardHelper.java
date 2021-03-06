/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tradefed.invoker.shard;

import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.IRescheduler;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.IBuildReceiver;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.IRuntimeHintProvider;
import com.android.tradefed.testtype.IShardableTest;
import com.android.tradefed.testtype.IStrictShardableTest;
import com.android.tradefed.testtype.suite.ITestSuite;
import com.android.tradefed.testtype.suite.ModuleMerger;
import com.android.tradefed.util.TimeUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Sharding strategy to create strict shards that do not report together, */
public class StrictShardHelper extends ShardHelper {

    /** {@inheritDoc} */
    @Override
    public boolean shardConfig(
            IConfiguration config, IInvocationContext context, IRescheduler rescheduler) {
        Integer shardCount = config.getCommandOptions().getShardCount();
        Integer shardIndex = config.getCommandOptions().getShardIndex();

        if (shardIndex == null) {
            return super.shardConfig(config, context, rescheduler);
        }
        if (shardCount == null) {
            throw new RuntimeException("shard-count is null while shard-index is " + shardIndex);
        }

        // Split tests in place, without actually sharding.
        if (!config.getCommandOptions().shouldUseTfSharding()) {
            // TODO: remove when IStrictShardableTest is removed.
            updateConfigIfSharded(config, shardCount, shardIndex);
        } else {
            List<IRemoteTest> listAllTests = getAllTests(config, shardCount, context);
            // We cannot shuffle to get better average results
            normalizeDistribution(listAllTests, shardCount);
            List<IRemoteTest> splitList;
            if (shardCount == 1) {
                // not sharded
                splitList = listAllTests;
            } else {
                splitList = splitTests(listAllTests, shardCount, shardIndex);
            }
            aggregateSuiteModules(splitList);
            config.setTests(splitList);
        }
        return false;
    }

    // TODO: Retire IStrictShardableTest for IShardableTest and have TF balance the list of tests.
    private void updateConfigIfSharded(IConfiguration config, int shardCount, int shardIndex) {
        List<IRemoteTest> testShards = new ArrayList<>();
        for (IRemoteTest test : config.getTests()) {
            if (!(test instanceof IStrictShardableTest)) {
                CLog.w(
                        "%s is not shardable; the whole test will run in shard 0",
                        test.getClass().getName());
                if (shardIndex == 0) {
                    testShards.add(test);
                }
                continue;
            }
            IRemoteTest testShard =
                    ((IStrictShardableTest) test).getTestShard(shardCount, shardIndex);
            testShards.add(testShard);
        }
        config.setTests(testShards);
    }

    /**
     * Helper to return the full list of {@link IRemoteTest} based on {@link IShardableTest} split.
     *
     * @param config the {@link IConfiguration} describing the invocation.
     * @param shardCount the shard count hint to be provided to some tests.
     * @param context the {@link IInvocationContext} of the parent invocation.
     * @return the list of all {@link IRemoteTest}.
     */
    private List<IRemoteTest> getAllTests(
            IConfiguration config, Integer shardCount, IInvocationContext context) {
        List<IRemoteTest> allTests = new ArrayList<>();
        for (IRemoteTest test : config.getTests()) {
            if (test instanceof IShardableTest) {
                // Inject current information to help with sharding
                if (test instanceof IBuildReceiver) {
                    ((IBuildReceiver) test).setBuild(context.getBuildInfos().get(0));
                }
                if (test instanceof IDeviceTest) {
                    ((IDeviceTest) test).setDevice(context.getDevices().get(0));
                }
                // Handling of the ITestSuite is a special case, we do not allow pool of tests
                // since each shard needs to be independent.
                if (test instanceof ITestSuite) {
                    ((ITestSuite) test).setShouldMakeDynamicModule(false);
                }

                Collection<IRemoteTest> subTests = ((IShardableTest) test).split(shardCount);
                if (subTests == null) {
                    // test did not shard so we add it as is.
                    allTests.add(test);
                } else {
                    allTests.addAll(subTests);
                }
            } else {
                // if test is not shardable we add it as is.
                allTests.add(test);
            }
        }
        return allTests;
    }

    /**
     * Split the list of tests to run however the implementation see fit. Sharding needs to be
     * consistent. It is acceptable to return an empty list if no tests can be run in the shard.
     *
     * <p>Implement this in order to provide a test suite specific sharding. The default
     * implementation strictly split by number of tests which is not always optimal. TODO: improve
     * the splitting criteria.
     *
     * @param fullList the initial full list of {@link IRemoteTest} containing all the tests that
     *     need to run.
     * @param shardCount the total number of shard that need to run.
     * @param shardIndex the index of the current shard that needs to run.
     * @return a list of {@link IRemoteTest} that need to run in the current shard.
     */
    private List<IRemoteTest> splitTests(
            List<IRemoteTest> fullList, int shardCount, int shardIndex) {
        List<List<IRemoteTest>> shards = new ArrayList<>();

        // Generate all the shards
        for (int i = 0; i < shardCount; i++) {
            List<IRemoteTest> shardList;
            if (i >= fullList.size()) {
                // Return empty list when we don't have enough tests for all the shards.
                shardList = new ArrayList<IRemoteTest>();
                shards.add(shardList);
                continue;
            }
            int numPerShard = (int) Math.ceil(fullList.size() / (float) shardCount);
            if (i == shardCount - 1) {
                // last shard take everything remaining.
                shardList = fullList.subList(i * numPerShard, fullList.size());
                shards.add(shardList);
                continue;
            }
            shardList = fullList.subList(i * numPerShard, numPerShard + (i * numPerShard));
            shards.add(shardList);
        }

        // do last minute rebalancing
        topBottom(shards);
        return shards.get(shardIndex);
    }

    /**
     * Move around predictably the tests in order to have a better uniformization of the tests in
     * each shard.
     */
    private void normalizeDistribution(List<IRemoteTest> listAllTests, int shardCount) {
        final int numRound = shardCount;
        final int distance = shardCount + 1;
        for (int i = 0; i < numRound; i++) {
            for (int j = 0; j < listAllTests.size(); j = j + distance) {
                // Push the test at the end
                IRemoteTest push = listAllTests.remove(j);
                listAllTests.add(push);
            }
        }
    }

    /**
     * Special handling for suite from {@link ITestSuite}. We aggregate the tests in the same shard
     * in order to optimize target_preparation step.
     *
     * @param tests the {@link List} of {@link IRemoteTest} for that shard.
     */
    private void aggregateSuiteModules(List<IRemoteTest> tests) {
        List<IRemoteTest> dupList = new ArrayList<>(tests);
        for (int i = 0; i < dupList.size(); i++) {
            if (dupList.get(i) instanceof ITestSuite) {
                // We iterate the other tests to see if we can find another from the same module.
                for (int j = i + 1; j < dupList.size(); j++) {
                    // If the test was not already merged
                    if (tests.contains(dupList.get(j))) {
                        if (dupList.get(j) instanceof ITestSuite) {
                            if (ModuleMerger.arePartOfSameSuite(
                                    (ITestSuite) dupList.get(i), (ITestSuite) dupList.get(j))) {
                                ModuleMerger.mergeSplittedITestSuite(
                                        (ITestSuite) dupList.get(i), (ITestSuite) dupList.get(j));
                                tests.remove(dupList.get(j));
                            }
                        }
                    }
                }
            }
        }
    }

    private void topBottom(List<List<IRemoteTest>> allShards) {
        // Generate approximate RuntimeHint for each shard
        int index = 0;
        CLog.e("============================");
        for (List<IRemoteTest> shard : allShards) {
            long aggTime = 0l;
            CLog.e("++++++++++++++++++ SHARD %s +++++++++++++++", index);
            for (IRemoteTest test : shard) {
                if (test instanceof IRuntimeHintProvider) {
                    aggTime += ((IRuntimeHintProvider) test).getRuntimeHint();
                }
            }
            CLog.e("Shard %s approximate time: %s", index, TimeUtil.formatElapsedTime(aggTime));
            index++;
            CLog.e("+++++++++++++++++++++++++++++++++++++++++++");
        }
        CLog.e("============================");
    }
}
