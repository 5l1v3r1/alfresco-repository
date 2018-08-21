/*
 * #%L
 * Alfresco Repository
 * %%
 * Copyright (C) 2005 - 2018 Alfresco Software Limited
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
package org.alfresco.repo.rendition2;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.rendition.RenditionPreventionRegistry;
import org.alfresco.service.cmr.rendition.RenditionPreventedException;
import org.alfresco.service.cmr.repository.ContentData;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.namespace.QName;
import org.alfresco.util.PropertyCheck;
import org.apache.commons.logging.Log;
import org.apache.xpath.operations.Bool;
import org.springframework.beans.factory.InitializingBean;

import java.util.Set;

/**
 * Contains common code used in TransformClients.
 *
 * @author adavis
 */
public abstract class AbstractTransformClient implements InitializingBean
{
    protected NodeService nodeService;
    private RenditionPreventionRegistry renditionPreventionRegistry;
    protected Log logger;
    private boolean alreadyChecked;

    public void setNodeService(NodeService nodeService)
    {
        this.nodeService = nodeService;
    }

    public void setRenditionPreventionRegistry(RenditionPreventionRegistry renditionPreventionRegistry)
    {
        this.renditionPreventionRegistry = renditionPreventionRegistry;
    }

    public void setAlreadyChecked(boolean alreadyChecked)
    {
        this.alreadyChecked = alreadyChecked;
    }

    @Override
    public void afterPropertiesSet() throws Exception
    {
        PropertyCheck.mandatory(this, "nodeService", nodeService);
        PropertyCheck.mandatory(this, "renditionPreventionRegistry", renditionPreventionRegistry);
    }

    protected ContentData getContentData(NodeRef sourceNodeRef)
    {
        if (!nodeService.exists(sourceNodeRef))
        {
            throw new IllegalArgumentException("The supplied sourceNodeRef "+sourceNodeRef+" does not exist any more.");
        }

        ContentData contentData = (ContentData) nodeService.getProperty(sourceNodeRef, ContentModel.PROP_CONTENT);
        if (contentData == null || contentData.getContentUrl() == null)
        {
            throw new IllegalArgumentException("The supplied sourceNodeRef "+sourceNodeRef+" has no content.");
        }
        return contentData;
    }

    /**
     * This method checks whether the specified source node is of a content class which has been registered for
     * rendition prevention.
     *
     * @param sourceNode the node to check.
     * @throws RenditionService2PreventedException if the source node is configured for rendition prevention.
     */
    // This code is based on the old RenditionServiceImpl.checkSourceNodeForPreventionClass(...)
    protected void checkSourceNodeForPreventionClass(NodeRef sourceNode)
    {
        // alreadyChecked avoids checking once in the RemoteTransformClient and again in the LocalTransformClient
        if (sourceNode != null && !alreadyChecked && nodeService.exists(sourceNode))
        {
            // A node's content class is its type and all its aspects.
            Set<QName> nodeContentClasses = nodeService.getAspects(sourceNode);
            nodeContentClasses.add(nodeService.getType(sourceNode));

            for (QName contentClass : nodeContentClasses)
            {
                if (renditionPreventionRegistry.isContentClassRegistered(contentClass))
                {
                    String msg = "Node " + sourceNode + " cannot be renditioned as it is of class " + contentClass;
                    logger.debug(msg);
                    throw new RenditionService2PreventedException(msg);
                }
            }
        }
    }
}
