/*
 * #%L
 * Alfresco Repository
 * %%
 * Copyright (C) 2005 - 2020 Alfresco Software Limited
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
package org.alfresco.repo.event2;

import java.io.Serializable;
import java.net.URI;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.repo.event2.filter.EventFilter;
import org.alfresco.repo.event2.filter.EventFilterRegistry;
import org.alfresco.repo.event2.filter.NodeTypeFilter;
import org.alfresco.repo.node.NodeServicePolicies.BeforeDeleteNodePolicy;
import org.alfresco.repo.node.NodeServicePolicies.OnAddAspectPolicy;
import org.alfresco.repo.node.NodeServicePolicies.OnCreateNodePolicy;
import org.alfresco.repo.node.NodeServicePolicies.OnRemoveAspectPolicy;
import org.alfresco.repo.node.NodeServicePolicies.OnUpdatePropertiesPolicy;
import org.alfresco.repo.policy.JavaBehaviour;
import org.alfresco.repo.policy.PolicyComponent;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.transaction.AlfrescoTransactionSupport;
import org.alfresco.repo.transaction.TransactionalResourceHelper;
import org.alfresco.service.cmr.dictionary.DictionaryService;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.descriptor.DescriptorService;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.alfresco.util.PropertyCheck;
import org.alfresco.util.transaction.TransactionListenerAdapter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * Generates events and sends them to an event topic.
 *
 * @author Jamal Kaabi-Mofrad
 */
public class EventGenerator implements InitializingBean, EventSupportedPolicies
{
    private static final Log LOGGER = LogFactory.getLog(EventGenerator.class);

    private PolicyComponent policyComponent;
    private NodeService nodeService;
    private NamespaceService namespaceService;
    private DictionaryService dictionaryService;
    private DescriptorService descriptorService;
    private EventFilterRegistry eventFilterRegistry;

    private EventFilter nodeTypeFilter;
    private NodeResourceHelper nodeResourceHelper;
    private EventTransactionListener transactionListener = new EventTransactionListener();

    @Override
    public void afterPropertiesSet()
    {
        PropertyCheck.mandatory(this, "policyComponent", policyComponent);
        PropertyCheck.mandatory(this, "nodeService", nodeService);
        PropertyCheck.mandatory(this, "namespaceService", namespaceService);
        PropertyCheck.mandatory(this, "dictionaryService", dictionaryService);
        PropertyCheck.mandatory(this, "descriptorService", descriptorService);
        PropertyCheck.mandatory(this, "eventFilterRegistry", eventFilterRegistry);

        policyComponent.bindClassBehaviour(OnCreateNodePolicy.QNAME, this,
                                           new JavaBehaviour(this, "onCreateNode"));
        policyComponent.bindClassBehaviour(BeforeDeleteNodePolicy.QNAME, this,
                                           new JavaBehaviour(this, "beforeDeleteNode"));
        policyComponent.bindClassBehaviour(OnUpdatePropertiesPolicy.QNAME, this,
                                           new JavaBehaviour(this, "onUpdateProperties"));
        policyComponent.bindClassBehaviour(OnAddAspectPolicy.QNAME, this,
                                           new JavaBehaviour(this, "onAddAspect"));
        policyComponent.bindClassBehaviour(OnRemoveAspectPolicy.QNAME, this,
                                           new JavaBehaviour(this, "onRemoveAspect"));

        this.nodeTypeFilter = eventFilterRegistry.getFilter(NodeTypeFilter.class)
                    .orElseThrow(() -> new AlfrescoRuntimeException(
                                "The filter '" + NodeTypeFilter.class.getName() + "' is not registered."));
        this.nodeResourceHelper = new NodeResourceHelper(nodeService, namespaceService, dictionaryService,
                                                         eventFilterRegistry);

    }

    public void setPolicyComponent(PolicyComponent policyComponent)
    {
        this.policyComponent = policyComponent;
    }

    public void setNodeService(NodeService nodeService)
    {
        this.nodeService = nodeService;
    }

    public void setNamespaceService(NamespaceService namespaceService)
    {
        this.namespaceService = namespaceService;
    }

    public void setDictionaryService(DictionaryService dictionaryService)
    {
        this.dictionaryService = dictionaryService;
    }

    public void setDescriptorService(DescriptorService descriptorService)
    {
        this.descriptorService = descriptorService;
    }

    public void setEventFilterRegistry(EventFilterRegistry eventFilterRegistry)
    {
        this.eventFilterRegistry = eventFilterRegistry;
    }

    @Override
    public void onCreateNode(ChildAssociationRef childAssocRef)
    {
        getEventConsolidator(childAssocRef.getChildRef()).ifPresent(
                    consolidator -> consolidator.onCreateNode(childAssocRef));
    }

    @Override
    public void onUpdateProperties(NodeRef nodeRef, Map<QName, Serializable> before, Map<QName, Serializable> after)
    {
        getEventConsolidator(nodeRef).ifPresent(
                    consolidator -> consolidator.onUpdateProperties(nodeRef, before, after));
    }

    @Override
    public void beforeDeleteNode(NodeRef nodeRef)
    {
        getEventConsolidator(nodeRef).ifPresent(
                    consolidator -> consolidator.beforeDeleteNode(nodeRef));
    }

    @Override
    public void onAddAspect(NodeRef nodeRef, QName aspectTypeQName)
    {
        getEventConsolidator(nodeRef).ifPresent(
                    consolidator -> consolidator.onAddAspect(nodeRef, aspectTypeQName));
    }

    @Override
    public void onRemoveAspect(NodeRef nodeRef, QName aspectTypeQName)
    {
        getEventConsolidator(nodeRef).ifPresent(
                    consolidator -> consolidator.onRemoveAspect(nodeRef, aspectTypeQName));
    }

    private EventInfo getEventInfo()
    {
        return new EventInfo().setTimestamp(ZonedDateTime.now())
                    .setId(UUID.randomUUID().toString())
                    .setTxnId(AlfrescoTransactionSupport.getTransactionId())
                    .setPrincipal(AuthenticationUtil.getFullyAuthenticatedUser())
                    .setSource(URI.create("/" + descriptorService.getCurrentRepositoryDescriptor().getId()));
    }

    /**
     * @return an {@code Optional} describing the {@link EventConsolidator} for the supplied {@code nodeRef} from
     * the current transaction context.
     */
    private Optional<EventConsolidator> getEventConsolidator(NodeRef nodeRef)
    {
        // Filter out excluded node types
        QName nodeType = nodeService.getType(nodeRef);
        if (nodeTypeFilter.isExcluded(nodeType))
        {
            if (LOGGER.isDebugEnabled())
            {
                LOGGER.debug("EventFilter - Excluding node with type: " + nodeType);
            }
            return Optional.empty();
        }

        Map<NodeRef, EventConsolidator> nodeEvents = getTxnResourceMap(transactionListener);
        if (nodeEvents.isEmpty())
        {
            AlfrescoTransactionSupport.bindListener(transactionListener);
        }

        EventConsolidator eventConsolidator = nodeEvents.get(nodeRef);
        if (eventConsolidator == null)
        {
            eventConsolidator = new EventConsolidator(nodeResourceHelper);
            nodeEvents.put(nodeRef, eventConsolidator);
        }

        return Optional.of(eventConsolidator);
    }

    private Map<NodeRef, EventConsolidator> getTxnResourceMap(Object resourceKey)
    {
        Map<NodeRef, EventConsolidator> map = AlfrescoTransactionSupport.getResource(resourceKey);
        if (map == null)
        {
            map = new LinkedHashMap<>(29);
            AlfrescoTransactionSupport.bindResource(resourceKey, map);
        }
        return map;
    }

    private void sendEvent(EventConsolidator consolidator)
    {
        if (consolidator.isTemporaryNode())
        {
            if (LOGGER.isTraceEnabled())
            {
                LOGGER.trace("Ignoring temporary node: " + consolidator.getNodeRef());
            }
            return;
        }

        if (LOGGER.isTraceEnabled())
        {
            LOGGER.trace("List of Events:" + consolidator.getEventTypes());
        }

        LOGGER.info("Sending event:" + consolidator.getRepoEvent(getEventInfo()));
    }

    private class EventTransactionListener extends TransactionListenerAdapter
    {
        @Override
        public void afterCommit()
        {
            final Map<NodeRef, EventConsolidator> changedNodes = TransactionalResourceHelper.getMap(this);
            for (Map.Entry<NodeRef, EventConsolidator> entry : changedNodes.entrySet())
            {
                EventConsolidator eventConsolidator = entry.getValue();
                sendEvent(eventConsolidator);
            }
        }
    }
}
