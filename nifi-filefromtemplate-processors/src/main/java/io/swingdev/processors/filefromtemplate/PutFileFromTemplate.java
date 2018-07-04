package io.swingdev.processors.filefromtemplate;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import com.hubspot.jinjava.Jinjava;
import com.hubspot.jinjava.JinjavaConfig;
import com.hubspot.jinjava.interpret.FatalTemplateErrorsException;
import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.loader.ResourceLocator;
import org.apache.commons.io.FileUtils;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.Validator;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.*;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Tags({"template", "jinja", "json"})
@CapabilityDescription("Creates a file by rendering a Jinja2 template with the FlowFile's attributes as the context.")
@SeeAlso({})
@WritesAttributes({@WritesAttribute(attribute="", description="")})
public class PutFileFromTemplate extends AbstractProcessor {
    public static final PropertyDescriptor PARSE_JSON_CONTENT_PROPERTY = new PropertyDescriptor
            .Builder().name("JSON content")
            .description("Parse Flow File content as JSON for the template.")
            .required(true)
            .defaultValue("false")
            .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
            .build();

    public static final PropertyDescriptor TEMPLATE_PROPERTY = new PropertyDescriptor
            .Builder().name("Template")
            .description("Jinja2 Template")
            .defaultValue(null)
            .required(false)
            .addValidator(Validator.VALID)
            .build();

    public static final PropertyDescriptor TEMPLATE_PATH_PROPERTY = new PropertyDescriptor
            .Builder().name("Template Path")
            .description("Jinja2 Template Path")
            .required(false)
            .expressionLanguageSupported(true)
            .addValidator(StandardValidators.FILE_EXISTS_VALIDATOR)
            .build();

    public static final PropertyDescriptor TEMPLATE_RESOURCES_PATH_PROPERTY = new PropertyDescriptor
            .Builder().name("Resources Path")
            .description("Jinja2 Path To All The Included Resources")
            .required(false)
            .addValidator(StandardValidators.FILE_EXISTS_VALIDATOR)
            .build();

    public static final PropertyDescriptor FILE_PREFIX_PROPERTY = new PropertyDescriptor
            .Builder().name("File Prefix")
            .description("File Prefix.")
            .defaultValue("rendered")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor FILE_SUFFIX_PROPERTY = new PropertyDescriptor
            .Builder().name("File Suffix")
            .description("File Suffix.")
            .defaultValue(".out")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final String DEFAULT_OUTPUT_PATH_SAVED_IN_ATTRIBUTE = "template.rendered.path";
    public static final PropertyDescriptor OUTPUT_PATH_SAVED_IN_ATTRIBUTE_PROPERTY = new PropertyDescriptor
            .Builder().name("Output Path attribute")
            .description("Output Path will be saved in this attribute")
            .required(true)
            .defaultValue(DEFAULT_OUTPUT_PATH_SAVED_IN_ATTRIBUTE)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final Relationship SUCCESS_RELATIONSHIP = new Relationship.Builder()
            .name("success")
            .description("On success")
            .build();
    public static final Relationship FAILURE_RELATIONSHIP = new Relationship.Builder()
            .name("failure")
            .description("On failure")
            .build();
    public static final Relationship JSON_PARSING_FAILURE_RELATIONSHIP = new Relationship.Builder()
            .name("json_failure")
            .description("On JSON parsing failure")
            .build();

    private List<PropertyDescriptor> descriptors;

    private Set<Relationship> relationships;

    volatile Jinjava jinjava;
    volatile JsonFactory jsonFactory;
    volatile ObjectMapper jsonMapper;

    Map<String, Object> validateAndParseJSONContent(ProcessSession processSession, FlowFile flowFile) throws JsonParseException {
        final Map<String, Object> jsonContent = Maps.newHashMap();

        try {
            processSession.read(flowFile, new InputStreamCallback() {
                @Override
                public void process(InputStream in) throws IOException {
                    JsonParser jp = jsonFactory.createParser(in);
                    jsonContent.putAll(jp.readValueAs(Map.class));
                }
            });
        } catch (ProcessException e) {
            if (e.getCause() instanceof JsonParseException) {
                throw (JsonParseException)e.getCause();
            } else {
                throw e;
            }
        }

        return jsonContent;
    }

    Map<String, Object> templateContextFromFlowFile(final ProcessContext context, final ProcessSession session, final FlowFile flowFile) throws JsonParseException {
        Map<String, Object> templateContext = Maps.newHashMap();

        templateContext.put("attributes", flowFile.getAttributes());

        if (context.getProperty(PARSE_JSON_CONTENT_PROPERTY).asBoolean() == Boolean.TRUE && flowFile.getSize() > 0) {
            Map<String, Object> jsonContent = validateAndParseJSONContent(session, flowFile);

            templateContext.put("content", jsonContent);
        }

        return templateContext;
    }

    String getTemplate(final ProcessContext context) throws IOException {
        String templateFilePath = context.getProperty(TEMPLATE_PATH_PROPERTY).evaluateAttributeExpressions().getValue();

        if (templateFilePath == null) {
            return context.getProperty(TEMPLATE_PROPERTY).getValue();
        } else {
            return FileUtils.readFileToString(new File(templateFilePath));
        }
    }

    File createOutputFile(final ProcessContext context) throws IOException{
        File tempFile = File.createTempFile(context.getProperty(FILE_PREFIX_PROPERTY).getValue(), context.getProperty(FILE_SUFFIX_PROPERTY).getValue());
        tempFile.deleteOnExit();

        return tempFile;
    }

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptors = new ArrayList<PropertyDescriptor>();
        descriptors.add(PARSE_JSON_CONTENT_PROPERTY);
        descriptors.add(TEMPLATE_RESOURCES_PATH_PROPERTY);
        descriptors.add(TEMPLATE_PATH_PROPERTY);
        descriptors.add(TEMPLATE_PROPERTY);
        descriptors.add(FILE_PREFIX_PROPERTY);
        descriptors.add(FILE_SUFFIX_PROPERTY);
        descriptors.add(OUTPUT_PATH_SAVED_IN_ATTRIBUTE_PROPERTY);
        this.descriptors = Collections.unmodifiableList(descriptors);

        final Set<Relationship> relationships = new HashSet<Relationship>();
        relationships.add(SUCCESS_RELATIONSHIP);
        relationships.add(FAILURE_RELATIONSHIP);
        relationships.add(JSON_PARSING_FAILURE_RELATIONSHIP);
        this.relationships = Collections.unmodifiableSet(relationships);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    public final String pathToResource(final ProcessContext context, String fullName) {
        String templateResourcesPath = context.getProperty(TEMPLATE_RESOURCES_PATH_PROPERTY).getValue();

        if (templateResourcesPath != null) {
            return Paths.get(templateResourcesPath, fullName).toAbsolutePath().toString();
        }

        String templateFilePath = context.getProperty(TEMPLATE_PATH_PROPERTY).evaluateAttributeExpressions().getValue();

        if (templateFilePath == null) {
            return null;
        }

        String templateFileContainingPath = Paths.get(templateFilePath).getParent().toAbsolutePath().toString();

        return Paths.get(templateFileContainingPath, fullName).toAbsolutePath().toString();
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) {
        final PutFileFromTemplate processor = this;

        jsonMapper = new ObjectMapper();
        jsonFactory = jsonMapper.getFactory();

        JinjavaConfig config = new JinjavaConfig();
        jinjava = new Jinjava(config);

        jinjava.setResourceLocator(new ResourceLocator() {
            @Override
            public String getString(String fullName, Charset encoding, JinjavaInterpreter interpreter) throws IOException {
                String pathToResource = processor.pathToResource(context, fullName);

                try {
                    return FileUtils.readFileToString(new File(pathToResource), encoding);
                } catch (IOException e) {
                    return null;
                }
            }
        });
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
		FlowFile flowFile = session.get();
		if ( flowFile == null ) {
			return;
		}

        final ComponentLog logger = getLogger();

        String template;
        try {
            template = getTemplate(context);
        } catch (IOException e) {
            logger.error("Could not read the template file.");
            session.transfer(flowFile, FAILURE_RELATIONSHIP);

            return;
        }

        Map<String, Object> templateContext;
        try {
            templateContext = templateContextFromFlowFile(context, session, flowFile);
        } catch (JsonParseException e) {
            logger.error("FlowFile {} did not have valid JSON content.", new Object[]{flowFile});
            session.transfer(flowFile, JSON_PARSING_FAILURE_RELATIONSHIP);

            return;
        }

        String renderedTemplate;
        try {
            renderedTemplate = jinjava.render(template, templateContext);
        } catch (FatalTemplateErrorsException e) {
            logger.error("Template rendering problem: {}", new Object[]{e.toString()});
            session.transfer(flowFile, FAILURE_RELATIONSHIP);

            return;
        }

        File outputFile;
        try {
            outputFile = createOutputFile(context);
            FileUtils.writeStringToFile(outputFile, renderedTemplate);
        } catch (IOException e) {
            logger.error("Could not create a temp. file.");
            session.transfer(flowFile, FAILURE_RELATIONSHIP);

            return;
        }

        flowFile = session.putAttribute(flowFile, context.getProperty(OUTPUT_PATH_SAVED_IN_ATTRIBUTE_PROPERTY).getValue(), outputFile.getAbsolutePath());
        session.transfer(flowFile, SUCCESS_RELATIONSHIP);
    }

}
