/*
 * #%L
 * Alfresco Repository
 * %%
 * Copyright (C) 2005 - 2016 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.heartbeat;

import org.alfresco.heartbeat.datasender.HBData;
import org.alfresco.repo.descriptor.DescriptorDAO;
import org.alfresco.repo.dictionary.CustomModelsInfo;
import org.alfresco.repo.transaction.RetryingTransactionHelper;
import org.alfresco.service.cmr.dictionary.CustomModelService;
import org.alfresco.service.cmr.repository.HBDataCollectorService;
import org.alfresco.service.descriptor.Descriptor;
import org.alfresco.service.transaction.TransactionService;
import org.alfresco.util.ApplicationContextHelper;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author eknizat
 */
public class UsageSystemDataCollectorTest
{

    private UsageSystemDataCollector usageSystemCollector;
    private HBDataCollectorService mockCollectorService;
    private DescriptorDAO mockDescriptorDAO;
    private List<HBData> collectedData;

    @Before
    public void setUp()
    {
        mockDescriptorDAO = mock(DescriptorDAO.class);
        mockCollectorService = mock(HBDataCollectorService.class);

        Descriptor mockDescriptor = mock(Descriptor.class);
        when(mockDescriptor.getId()).thenReturn("mock_id");
        when(mockDescriptorDAO.getDescriptor()).thenReturn(mockDescriptor);

        usageSystemCollector = new UsageSystemDataCollector("acs.repository.usage.system");
        usageSystemCollector.setCollectorVersion("1.0");
        usageSystemCollector.setHbDataCollectorService(mockCollectorService);
        usageSystemCollector.setCurrentRepoDescriptorDAO(mockDescriptorDAO);

        collectedData = usageSystemCollector.collectData();
    }

    @Test
    public void testHBDataFields()
    {
        for(HBData data : this.collectedData)
        {
            assertNotNull(data.getCollectorId());
            assertNotNull(data.getCollectorVersion());
            assertNotNull(data.getSchemaVersion());
            assertNotNull(data.getSystemId());
            assertNotNull(data.getTimestamp());
        }
    }

    @Test
    public void testSystemUsageDataIsCollected()
    {
        HBData systemUsage = grabDataByCollectorId(usageSystemCollector.getCollectorId());
        assertNotNull("Repository usage data missing.", systemUsage);

        Map<String,Object> data = systemUsage.getData();
        assertTrue(data.containsKey("memFree"));
        assertTrue(data.containsKey("memMax"));
        assertTrue(data.containsKey("memTotal"));
    }

    private HBData grabDataByCollectorId(String collectorId)
    {
        for (HBData d : this.collectedData)
        {
            if(d.getCollectorId()!=null && d.getCollectorId().equals(collectorId))
            {
                return d;
            }
        }
        return null;
    }
}
