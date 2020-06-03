package com.hedera.mirror.importer.parser.record.transactionhandler;

/*-
 * ‌
 * Hedera Mirror Node
 * ​
 * Copyright (C) 2019 - 2020 Hedera Hashgraph, LLC
 * ​
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
 * ‍
 */

import com.hederahashgraph.api.proto.java.Timestamp;
import java.util.List;
import javax.inject.Named;
import lombok.AllArgsConstructor;

import com.hedera.mirror.importer.domain.Entities;
import com.hedera.mirror.importer.domain.EntityId;
import com.hedera.mirror.importer.parser.domain.RecordItem;
import com.hedera.mirror.importer.util.Utility;

@Named
@AllArgsConstructor
public class ConsensusUpdateTopicTransactionHandler implements TransactionHandler {

    @Override
    public EntityId getEntityId(RecordItem recordItem) {
        return EntityId.of(recordItem.getTransactionBody().getConsensusUpdateTopic().getTopicID());
    }

    @Override
    public boolean updatesEntity() {
        return true;
    }

    @Override
    public void updateEntity(Entities entity, RecordItem recordItem, List<EntityId> linkedEntityIds) {
        var updateTopic = recordItem.getTransactionBody().getConsensusUpdateTopic();
        if (updateTopic.hasExpirationTime()) {
            Timestamp expirationTime = updateTopic.getExpirationTime();
            entity.setExpiryTimeNs(Utility.timestampInNanosMax(expirationTime));
        }
        // Looks up (in the big cache) or creates new id.
        EntityId autoRenewAccount = EntityId.of(updateTopic.getAutoRenewAccount());
        if (autoRenewAccount != null) {
            linkedEntityIds.add(autoRenewAccount);
            entity.setAutoRenewAccount(autoRenewAccount);
        }
        if (updateTopic.hasAutoRenewPeriod()) {
            entity.setAutoRenewPeriod(updateTopic.getAutoRenewPeriod().getSeconds());
        }
        if (updateTopic.hasAdminKey()) {
            entity.setKey(updateTopic.getAdminKey().toByteArray());
        }
        if (updateTopic.hasSubmitKey()) {
            entity.setSubmitKey(updateTopic.getSubmitKey().toByteArray());
        }
        if (updateTopic.hasMemo()) {
            entity.setMemo(updateTopic.getMemo().getValue());
        }
    }
}
