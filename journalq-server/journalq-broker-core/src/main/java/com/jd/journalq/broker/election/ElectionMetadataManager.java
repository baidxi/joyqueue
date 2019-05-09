/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jd.journalq.broker.election;

import com.alibaba.fastjson.TypeReference;

import com.alibaba.fastjson.JSON;
import com.jd.journalq.broker.cluster.ClusterManager;
import com.jd.journalq.domain.PartitionGroup;
import com.jd.journalq.domain.TopicConfig;
import com.jd.journalq.domain.TopicName;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 选举中需要保存的metadata，每个PartitionGroup需要保存节点信息，raft还需要保存currentTerm和voteFor
 * 在每次重新选举时都需要保存metadata信息，选举任务启动时恢复metadata
 * author: zhuduohui
 * email: zhuduohui@jd.com
 * date: 2018/8/15
 */
public class ElectionMetadataManager {
    private static Logger logger = LoggerFactory.getLogger(ElectionMetadataManager.class);

    private String fileName;
    private String path;

    private Map<TopicPartitionGroup, ElectionMetadata> metadataMap = new ConcurrentHashMap<>();

    public ElectionMetadataManager(String fileName, String path) {
        this.fileName = fileName;
        this.path = path;
    }


    /**
     * 从文件恢复元数据
     * @param electionManager election manager
     */
    void recover(ElectionManager electionManager) throws Exception {
        try {
            File file = new File(fileName);
            if (file.exists()) recoverMetadataOld(file);
            else recoverMetadata();

            restoreLeaderElections(electionManager);
        } catch (Exception e) {
            logger.info("Recover election metadata fail", e);
        }
    }

    /**
     * 恢复旧的格式的元数据
     * @param file 存储元数据的文件
     */
    private void recoverMetadataOld(File file) throws IOException {
        logger.info("recover metadata from {}", file.getAbsoluteFile());

        String metadataStr = (String) readConfigFile(file, String.class, "");
        if (metadataStr != null && !metadataStr.isEmpty()) {
            Map<TopicPartitionGroup, ElectionMetadataOld> oldMetadataMap = JSON.parseObject(metadataStr,
                    new TypeReference<ConcurrentHashMap<TopicPartitionGroup, ElectionMetadataOld>>() {
                    });
            for (TopicPartitionGroup topicPartitionGroup : oldMetadataMap.keySet()) {
                ElectionMetadataOld metadataOld = oldMetadataMap.get(topicPartitionGroup);
                try (ElectionMetadata metadata = ElectionMetadata.Build.create(path, topicPartitionGroup)
                        .allNodes(metadataOld.getAllNodes()).currentTerm(metadataOld.getCurrentTerm())
                        .localNode(metadataOld.getLocalNodeId()).learners(metadataOld.getLearners())
                        .leaderId(metadataOld.getLeaderId()).electionType(metadataOld.getElectType())
                        .votedFor(metadataOld.getVotedFor()).build()) {
                    updateElectionMetadata(topicPartitionGroup, metadata);
                }
            }
        }
        boolean ret = file.delete();
        if (!ret) {
            logger.info("Recover old metadata, delete file {} fail", file.getAbsoluteFile());
            throw new IOException("Delete file " + file.getAbsoluteFile() + " fail");
        }
    }

    /**
     * 恢复元数据
     */
    private void recoverMetadata() throws IOException {
        File root = new File(this.path);
        if (!root.exists()) {
            boolean ret = root.mkdir();
            if (!ret) {
                logger.info("Recover election metadata create dir {} fail",
                        root.getAbsoluteFile());
                throw new IOException("Delete file " + root.getAbsoluteFile() + " fail");
            }
        }

        File[] topicDirs = root.listFiles();
        if (topicDirs == null) return;

        for (File topicDir : topicDirs) {
            if (!topicDir.isDirectory()) continue;

            String topic = topicDir.getName().replace('@', File.separatorChar);
            File[] pgsFiles = topicDir.listFiles();
            if (pgsFiles == null) continue;

            for (File filePg : pgsFiles) {
                if (!StringUtils.isNumeric(filePg.getName())) {
                    logger.warn("Recover election metadata of topic {} fail, pg is {}",
                            topic, filePg.getName());
                    continue;
                }
                TopicPartitionGroup partitionGroup = new TopicPartitionGroup(topic, Integer.valueOf(filePg.getName()));
                try (ElectionMetadata metadata = ElectionMetadata.Build.create(this.path, partitionGroup).build()) {
                    metadata.recover();
                    metadataMap.put(partitionGroup, metadata);
                } catch (Exception e) {
                    logger.info("Create election metadata fail", e);
                }
            }
        }
    }

    @Deprecated
    private static Object readConfigFile(File file, Class objClass, Object defValue) throws IOException {
        if (file == null || !file.exists()) {
            if (defValue == null)
                throw new IOException("file is null or not exists");
            else
                return defValue;
        }

        byte[] buf;
        try(FileInputStream reader = new FileInputStream(file)) {
            if (reader.available() > 3 * 1024 * 1024) {
                throw new IOException("file " + file.getAbsolutePath() + " is too large to process");
            } else if (reader.available() == 0) {
                return defValue;
            }
            buf = new byte[reader.available()];
            reader.read(buf);
        }

        if (objClass.equals(String.class)) {
            return new String(buf);
        } else {
            Object retObj = JSON.parseObject(buf, objClass);
            return (retObj == null ? defValue : retObj);
        }
    }

    /**
     * 通过PartitionGroup获取election metadata
     * @param topicPartitionGroup topic 和 partition group id
     * @return 元数据
     */
    ElectionMetadata getElectionMetadata(TopicPartitionGroup topicPartitionGroup) {
        return metadataMap.get(topicPartitionGroup);
    }

    /**
     * 更新metadata信息，每次更新都直接写到文件
     * @param topicPartitionGroup topic 和 partition group id
     * @param metadata 元数据
     */
    synchronized void updateElectionMetadata(TopicPartitionGroup topicPartitionGroup, ElectionMetadata metadata) {
        metadataMap.put(topicPartitionGroup, metadata);
        metadata.flush();
    }

    /**
     * 删除partition group对应的election metadata， 每次删除都更新文件
     * @param topicPartitionGroup topic 和 partition group id
     */
    synchronized void removeElectionMetadata(TopicPartitionGroup topicPartitionGroup) {
        try {
            metadataMap.remove(topicPartitionGroup);
            File topicDir = new File(this.path + File.separator + topicPartitionGroup.getTopic()
                    .replace(File.separatorChar, '@'));
            File[] pgFiles = topicDir.listFiles();
            if (pgFiles == null) return;

            for (File pgFile : pgFiles) {
                if (Integer.valueOf(pgFile.getName()) == topicPartitionGroup.getPartitionGroupId()) {
                    boolean ret = pgFile.delete();
                    if (!ret) {
                        logger.info("Remove election metadata, delete file {} fail",
                                pgFile.getAbsoluteFile());
                    }
                }
            }
            pgFiles = topicDir.listFiles();
            if (pgFiles == null || pgFiles.length == 0) {
                boolean ret = topicDir.delete();
                if (!ret) {
                    logger.info("Remove election metadata, delete dir {} fail",
                            topicDir.getAbsoluteFile());
                }
            }

        } catch (Exception e) {
            logger.error("Remove election metadata of {} fail", topicPartitionGroup, e);
        }
    }

    /**
     * 删除所有election metadata
     */
    private synchronized void clearElectionMetadata() {
        metadataMap.clear();
        File path = new File(this.path);
        boolean ret = path.delete();
        if (!ret) {
            logger.info("Clear election metadata, delete dir {} fail",
                    path.getAbsoluteFile());
        }
    }

    /**
     * 根据metadata恢复leader election
     */
    private synchronized void restoreLeaderElections(ElectionManager electionManager) {

        for (TopicPartitionGroup topicPartitionGroup : metadataMap.keySet()) {
            try {
                electionManager.restoreLeaderElection(topicPartitionGroup, metadataMap.get(topicPartitionGroup));
            } catch (Exception e) {
                logger.warn("Restore leader election fail", e);
            }
        }
    }

    synchronized String describe() {
        return JSON.toJSONString(metadataMap);
    }

    synchronized String describe(String topic, int partitionGroup) {
        return JSON.toJSONString(metadataMap.get(new TopicPartitionGroup(topic, partitionGroup)));
    }

    private ElectionMetadata generateMetadataFromPartitionGroup(String topic, PartitionGroup partitionGroup, int localBrokerId) throws IOException {
        return ElectionMetadata.Build.create(path, new TopicPartitionGroup(topic, partitionGroup.getGroup()))
                .electionType(partitionGroup.getElectType()).leaderId(partitionGroup.getLeader())
                .learners(partitionGroup.getLearners()).localNode(localBrokerId)
                .currentTerm(partitionGroup.getTerm()).allNodes(partitionGroup.getBrokers().values().stream()
                        .map(broker -> new DefaultElectionNode(broker.getIp() + ":" + broker.getBackEndPort(), broker.getId()))
                        .collect(Collectors.toList())).build();
    }

    /**
     * 从name service同步选举元数据
     * @param clusterManager 集群管理
     */
    synchronized void syncElectionMetadataFromNameService(ClusterManager clusterManager) {
        clearElectionMetadata();

        Map<TopicName, TopicConfig> topicConfigs = clusterManager.getNameService().getTopicConfigByBroker(clusterManager.getBrokerId());
        for (TopicConfig topicConfig : topicConfigs.values()) {
            topicConfig.getPartitionGroups().values().stream()
                    .filter((pg) -> pg.getReplicas().contains(clusterManager.getBrokerId()))
                    .forEach((pg) -> {
                        try (ElectionMetadata metadata = generateMetadataFromPartitionGroup(topicConfig.getName().getFullName(),
                                    pg, clusterManager.getBrokerId())) {
                            updateElectionMetadata(new TopicPartitionGroup(topicConfig.getName().getFullName(),
                                    pg.getGroup()), metadata);
                        } catch (Exception e) {
                            logger.info("Sync election metadata of topic {} pg {} from name service fail",
                                    pg.getTopic(), pg.getGroup(), e);
                        }
                    });
        }
    }

    /**
     * 更新term
     * @param topic topic
     * @param partitionGroup partition group
     * @param term 新的term
     */
    synchronized void updateTerm(String topic, int partitionGroup, int term) {
        ElectionMetadata metadata = metadataMap.get(new TopicPartitionGroup(topic, partitionGroup));
        metadata.setCurrentTerm(term);
        metadata.flush();
    }

    public void close() {
        metadataMap.clear();
    }

}
