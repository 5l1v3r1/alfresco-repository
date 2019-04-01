/*
 * #%L
 * Alfresco Repository
 * %%
 * Copyright (C) 2019 Alfresco Software Limited
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
package org.alfresco.repo.content.transform;

import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.MimetypeService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.transform.client.model.config.ExtensionMap;
import org.alfresco.transform.client.model.config.TransformServiceRegistry;
import org.alfresco.transform.client.model.config.TransformServiceRegistryImpl;
import org.alfresco.transform.client.model.config.TransformStep;
import org.alfresco.transform.client.model.config.Transformer;
import org.alfresco.util.PropertyCheck;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.InitializingBean;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Implements {@link TransformServiceRegistry} providing a mechanism of validating if a local transformation
 * (based on {@link LocalTransformer} request is supported. It also extends this interface to provide a
 * {@link #transform} method.
 * @author adavis
 */
public class LocalTransformServiceRegistry extends TransformServiceRegistryImpl implements InitializingBean
{
    private static final Log log = LogFactory.getLog(LocalTransformerImpl.class);

    private MimetypeService mimetypeService;
    private String transformServiceConfigFile;
    private boolean enabled = true;
    private boolean firstTime = true;
    private Properties properties;
    private TransformerDebug transformerDebug;

    private Map<String, LocalTransformer> transformers = new HashMap<>();

    public void setMimetypeService(MimetypeService mimetypeService)
    {
        this.mimetypeService = mimetypeService;
    }

    public void setTransformServiceConfigFile(String transformServiceConfigFile)
    {
        this.transformServiceConfigFile = transformServiceConfigFile;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    /**
     * The Alfresco global properties.
     */
    public void setProperties(Properties properties)
    {
        this.properties = properties;
    }

    public void setTransformerDebug(TransformerDebug transformerDebug)
    {
        this.transformerDebug = transformerDebug;
    }

    @Override
    public void afterPropertiesSet() throws Exception
    {
        PropertyCheck.mandatory(this, "mimetypeService", mimetypeService);
        PropertyCheck.mandatory(this, "transformServiceConfigFile", transformServiceConfigFile);
        PropertyCheck.mandatory(this, "properties", properties);
        PropertyCheck.mandatory(this, "transformerDebug", transformerDebug);

        setExtensionMap(new ExtensionMap() {
            @Override
            public String toMimetype(String extension)
            {
                return mimetypeService.getMimetype(extension);
            }

            @Override
            public String toExtension(String mimetype)
            {
                return mimetypeService.getExtension(mimetype);
            }
        });
        super.afterPropertiesSet();

        try (Reader reader = new BufferedReader(new InputStreamReader(getClass().getClassLoader().
                getResourceAsStream(transformServiceConfigFile))))
        {
            register(reader);
        }
    }

    @Override
    public void register(Transformer transformer)
    {
        super.register(transformer);

        try
        {
            String name = transformer.getName();

            if (name == null || transformers.get(name) != null)
            {
                throw new IllegalArgumentException("Local transformers must exist and have unique names (" + name + ").");
            }

            ExtensionMap extensionMap = getExtensionMap();
            List<TransformStep> transformPipeline = transformer.getTransformPipeline();
            LocalTransformer localTransformer;
            if (transformPipeline == null)
            {
                String baseUrl = getBaseUrl(name);
                int startupRetryPeriodSeconds = getStartupRetryPeriodSeconds(name);
                localTransformer = new LocalTransformerImpl(name, extensionMap, transformerDebug, baseUrl, startupRetryPeriodSeconds
                );
            }
            else
            {
                int transformerCount = transformPipeline.size();
                if (transformerCount <= 1)
                {
                    throw new IllegalArgumentException("Local pipeline transformer " + name +
                            " must have more than one intermediate transformer defined.");
                }

                localTransformer = new LocalPipelineTransformer(name, extensionMap, transformerDebug);
                for (int i=0; i < transformerCount; i++)
                {
                    TransformStep intermediateTransformerStep = transformPipeline.get(i);
                    String intermediateTransformerName = intermediateTransformerStep.getName();
                    if (name == null || transformers.get(name) != null)
                    {
                        throw new IllegalArgumentException("Local pipeline transformer " + name +
                                " did not specified an intermediate transformer name.");
                    }

                    LocalTransformer intermediateTransformer = transformers.get(intermediateTransformerName);
                    if (intermediateTransformer == null)
                    {
                        throw new IllegalArgumentException("Local pipeline transformer " + name +
                                " specified an intermediate transformer (" +
                                intermediateTransformerName + " that has not previously been defined.");
                    }

                    String targetExt = intermediateTransformerStep.getTargetExt();
                    if (i == transformerCount-1)
                    {
                        if (targetExt != null)
                        {
                            throw new IllegalArgumentException("Local pipeline transformer " + name +
                                    " must not specify targetExt for the final intermediate transformer, " +
                                    "as this is defined via the supportedSourceAndTargetList.");
                        }
                    }
                    else
                    {
                        if (targetExt == null)
                        {
                            throw new IllegalArgumentException("Local pipeline transformer " + name +
                                    " must specify targetExt for all intermediate transformers except for the final one.");
                        }
                    }
                    ((LocalPipelineTransformer)localTransformer).addIntermediateTransformer(intermediateTransformer, targetExt);
                }
            }
            transformers.put(name, localTransformer);
        }
        catch (IllegalArgumentException e)
        {
            String msg = e.getMessage();
            log.error(msg);
        }
    }

    private String getBaseUrl(String name)
    {
        String baseUrlName = name + ".url";
        String baseUrl = properties.getProperty(baseUrlName);
        if (baseUrl == null)
        {
            throw new IllegalArgumentException("Local transformer property " + baseUrlName + " was not set");
        }
        return baseUrl;
    }

    private int getStartupRetryPeriodSeconds(String name)
    {
        String startupRetryPeriodSecondsName = name + ".startupRetryPeriodSeconds";
        String property = properties.getProperty(startupRetryPeriodSecondsName, "0");
        int startupRetryPeriodSeconds;
        try
        {
            startupRetryPeriodSeconds = Integer.parseInt(property);
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("Local transformer property " + startupRetryPeriodSecondsName +
                    " should be an integer");
        }
        return startupRetryPeriodSeconds;
    }

    @Override
    public long getMaxSize(String sourceMimetype, String targetMimetype, Map<String, String> options, String renditionName)
    {
        // This message is not logged if placed in afterPropertiesSet
        if (firstTime)
        {
            firstTime = false;
            transformerDebug.debug("Local transforms are " + (enabled ? "enabled" : "disabled"));
        }

        return enabled
                ? super.getMaxSize(sourceMimetype, targetMimetype, options, renditionName)
                : 0;
    }

    public void transform(ContentReader reader, ContentWriter writer, Map<String, String> actualOptions,
                          String renditionName, NodeRef sourceNodeRef) throws Exception
    {

        String sourceMimetype = reader.getMimetype();
        String targetMimetype = writer.getMimetype();
        long sourceSizeInBytes = reader.getSize();
        String name = getTransformerName(sourceMimetype, sourceSizeInBytes, targetMimetype, actualOptions, renditionName);

        LocalTransformer localTransformer = transformers.get(name);
        localTransformer.transform(reader, writer, actualOptions, renditionName, sourceNodeRef);
    }
}
