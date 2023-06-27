package com.composum.ai.composum.bundle;

import static com.composum.sling.core.servlet.ServletOperationSet.Method.GET;
import static com.composum.sling.core.servlet.ServletOperationSet.Method.POST;
import static org.apache.commons.lang3.StringUtils.isAnyBlank;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNoneBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.SyntheticResource;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.ServletResolverConstants;
import org.jetbrains.annotations.NotNull;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.composum.ai.backend.base.service.GPTException;
import com.composum.ai.backend.base.service.chat.GPTChatCompletionService;
import com.composum.ai.backend.base.service.chat.GPTContentCreationService;
import com.composum.ai.backend.base.service.chat.GPTTranslationService;
import com.composum.ai.composum.bundle.model.TranslationDialogModel;
import com.composum.sling.core.BeanContext;
import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.servlet.AbstractServiceServlet;
import com.composum.sling.core.servlet.ServletOperation;
import com.composum.sling.core.servlet.ServletOperationSet;
import com.composum.sling.core.servlet.Status;
import com.composum.sling.core.util.XSS;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Servlet providing the various services from the backend as servlet, which are useable for the authors.
 */
@Component(service = Servlet.class,
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum AI Backend Servlet",
                ServletResolverConstants.SLING_SERVLET_PATHS + "=/bin/cpm/platform/ai/authoring",
                ServletResolverConstants.SLING_SERVLET_METHODS + "=" + HttpConstants.METHOD_GET,
                ServletResolverConstants.SLING_SERVLET_METHODS + "=" + HttpConstants.METHOD_POST
        })
public class AIServlet extends AbstractServiceServlet {

    private static final Logger LOG = LoggerFactory.getLogger(AIServlet.class);

    /**
     * Parameter to transmit a text on which ChatGPT is to operate - not as instructions but as data.
     */
    public static final String PARAMETER_TEXT = "text";

    /**
     * Parameter to transmit a prompt on which ChatGPT is to operate - that is, the instructions.
     */
    public static final String PARAMETER_PROMPT = "prompt";

    /**
     * Optional numerical parameter limiting the number of words to be generated. That might lead to cutoff or actual wordcount, depending on the operation, and is usually only quite approximate.
     */
    public static final String PARAMETER_MAXWORDS = "maxtokens";

    /**
     * Optional numerical parameter limiting the number of tokens (about 3/4 english word on average) to be generated.
     * That might lead to cutoff, as this is a hard limit and ChatGPT doesn't know about that during generation.
     * So it's advisable to specify the desired text length in the prompt, too.
     */
    public static final String PARAMETER_MAXTOKENS = "maxtokens";


    /**
     * The path to a resource, given as parameter.
     */
    public static final String PARAMETER_PATH = "path";

    /**
     * Property name, given as parameter.
     */
    public static final String PARAMETER_PROPERTY = "property";

    public static final String PARAMETER_RICHTEXT = "richText";

    /**
     * Key for {@link Status#data(String)} - toplevel key in the servlet result.
     */
    public static final String RESULTKEY = "result";

    /**
     * Key in the result that transmits the generated description.
     */
    public static final String RESULTKEY_DESCRIPTIION = "description";

    /**
     * Key in the result that transmits the generated list of keywords.
     */
    public static final String RESULTKEY_KEYWORDS = "keywords";

    /**
     * Key in the result that transmits the generated text.
     */
    public static final String RESULTKEY_TEXT = "text";

    /**
     * Key in the result that transmits a list of translations (currently only one, but might be extended later.)
     */
    public static final String RESULTKEY_TRANSLATION = "translation";

    /**
     * Used to transmit whether the response was complete (finishreason {@link com.composum.ai.backend.base.service.chat.GPTFinishReason#STOP} or length restriction {@link com.composum.ai.backend.base.service.chat.GPTFinishReason#LENGTH}). Lowercase String.
     */
    public static final String RESULTKEY_FINISHREASON = "finishreason";

    /**
     * If set to true for operations that support it, the actual response can be streamed with {@link StreamResponseOperation}
     * in a followup GET request.
     */
    public static final String PARAMETER_STREAMING = "streaming";

    /**
     * Transmits the ID of the stream to {@link StreamResponseOperation}.
     */
    public static final String PARAMETER_STREAMID = "streamid";

    /**
     * Alternative to {@link #RESULTKEY_TEXT} when the response will be streamed.
     */
    public static final String RESULTKEY_STREAMID = "streamid";

    /**
     * Session contains a map at this key thqt maps the streamids to the streaming handle.
     */
    public static final String SESSIONKEY_STREAMING = AIServlet.class.getName() + ".streaming";

    @Reference
    protected GPTChatCompletionService chatService;

    @Reference
    protected GPTTranslationService translationService;

    @Reference
    protected GPTContentCreationService contentCreationService;

    @Reference
    protected ApproximateMarkdownService markdownService;

    protected BundleContext bundleContext;

    protected Cache<List<String>, String> translationCache;


    public enum Extension {json, sse}

    public enum Operation {translate, keywords, description, prompt, create, streamresponse}

    protected final ServletOperationSet<Extension, Operation> operations = new ServletOperationSet<>(Extension.json);

    @Override
    protected ServletOperationSet<Extension, Operation> getOperations() {
        return operations;
    }

    @Override
    public void init() throws ServletException {
        super.init();
        // FIXME(hps,19.04.23) only use POST later, but for now, GET is easier to test
        for (ServletOperationSet.Method method : List.of(GET, POST)) {
            operations.setOperation(method, Extension.json, Operation.translate,
                    new TranslateOperation());
            operations.setOperation(method, Extension.json, Operation.keywords,
                    new KeywordsOperation());
            operations.setOperation(method, Extension.json, Operation.description,
                    new DescriptionOperation());
            operations.setOperation(method, Extension.json, Operation.prompt,
                    new PromptOperation());
            operations.setOperation(method, Extension.json, Operation.create,
                    new CreateOperation());
        }
        operations.setOperation(GET, Extension.sse, Operation.streamresponse,
                new StreamResponseOperation());
        // FIXME(hps,19.04.23) at least make it configurable.
        translationCache = CacheBuilder.newBuilder()
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .expireAfterWrite(120, TimeUnit.MINUTES)
                .maximumSize(128)  // each entry can be at most a few kilobytes, so that'd be less than one megabyte
                .removalListener(notification -> {
                    LOG.debug("Removing translation from cache: {}", notification.getKey());
                })
                .build();
    }

    @Activate
    public void activate(final BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    /**
     * Saves stream for streaming requests into session, to be retrieved with {@link #retrieveStream(String, SlingHttpServletRequest)} during a {@link StreamResponseOperation}.
     */
    protected String saveStream(EventStream stream, SlingHttpServletRequest request) {
        String streamId = UUID.randomUUID().toString();
        Map<String, EventStream> streams = (Map<String, EventStream>) request.getSession().getAttribute(SESSIONKEY_STREAMING);
        if (streams == null) {
            streams = (Map<String, EventStream>) (Map) CacheBuilder.newBuilder()
                    .maximumSize(10).expireAfterWrite(1, TimeUnit.MINUTES).build().asMap();
            request.getSession().setAttribute(SESSIONKEY_STREAMING, streams);
        }
        streams.put(streamId, stream);
        stream.setId(streamId);
        return streamId;
    }

    protected EventStream retrieveStream(String streamId, SlingHttpServletRequest request) {
        Map<String, EventStream> streams = (Map<String, EventStream>) request.getSession().getAttribute(SESSIONKEY_STREAMING);
        return streams.get(streamId);
    }

    protected abstract class AbstractGPTServletOperation implements ServletOperation {

        /**
         * Frame implementation with common code
         */
        @Override
        public final void doIt(@Nonnull SlingHttpServletRequest request, @Nonnull SlingHttpServletResponse response, @Nullable ResourceHandle resource) throws RepositoryException, IOException, ServletException {
            Status status = new Status(request, response, LOG);

            try {
                performOperation(status, request, response);
            } catch (GPTException e) {
                status.error("Error accessing ChatGPT", e);
                status.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            } catch (RuntimeException e) {
                status.error("Internal error", e);
                status.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }

            status.sendJson();
        }

        protected abstract void performOperation(Status status, SlingHttpServletRequest request, SlingHttpServletResponse response);

        protected Integer getOptionalInt(Status status, SlingHttpServletRequest request, String parameterName) {
            String parameter = request.getParameter(parameterName);
            if (parameter != null) {
                try {
                    return Integer.parseInt(parameter);
                } catch (NumberFormatException e) {
                    status.error("Invalid nonnumeric value for parameter " + parameterName + ": " + parameter);
                }
            }
            return null;
        }
    }

    /**
     * Servlet representation of {@link GPTTranslationService}, specifically
     * {@link GPTTranslationService#singleTranslation(String, String, String, boolean)} and the streaming version,
     * with arguments text, sourceLanguage, targetLanguage, {@value #PARAMETER_RICHTEXT} .
     * Input are the parameters text, sourceLanguage, targetLanguage, output is in data.result.translation
     * a list containing the translation as (currently) a single string.
     * We use a list since it might be sensible to create multiple translation variants in the future, if requested.
     */
    // http://localhost:9090/bin/cpm/platform/ai/authoring.translate.json?sourceLanguage=en&targetLanguage=de&text=Hello%20World
    public class TranslateOperation extends AbstractGPTServletOperation {

        @Override
        protected void performOperation(Status status, SlingHttpServletRequest request, SlingHttpServletResponse response) {
            String text = XSS.filter(request.getParameter(PARAMETER_TEXT));
            String path = request.getParameter(PARAMETER_PATH);
            if (isBlank(path)) {
                path = request.getRequestPathInfo().getSuffix();
            }
            String property = request.getParameter(PARAMETER_PROPERTY);
            if (isBlank(text) && isAnyBlank(path, property)) {
                status.error("No text or path *and* property given");
            }
            String sourceLanguage = status.getRequiredParameter("sourceLanguage", null, "No sourceLanguage given");
            String targetLanguage = request.getParameter("targetLanguage");
            boolean streaming = Boolean.TRUE.toString().equalsIgnoreCase(request.getParameter(PARAMETER_STREAMING));
            boolean richtext = Boolean.TRUE.toString().equalsIgnoreCase(request.getParameter(PARAMETER_RICHTEXT));
            if (isNoneBlank(path, property)) {
                ResourceResolver resolver = request.getResourceResolver();
                Resource nodeResource = resolver.getResource(path);
                if (nodeResource == null) {
                    status.error("No resource found at " + path + '/' + property);
                } else {
                    Resource propertyResource = nodeResource.getChild(property);
                    if (propertyResource == null) {
                        // that's possible if there is a i18n value but no value for the default language yet
                        propertyResource = new SyntheticResource(resolver, path + '/' + property, null);
                    }
                    BeanContext context = new BeanContext.Servlet(getServletContext(), bundleContext, request, response);
                    TranslationDialogModel model = context.withResource(propertyResource).adaptTo(TranslationDialogModel.class);
                    if (model == null) {
                        status.error("Could not read from " + path + '/' + property);
                    } else {
                        text = model.getValueForLanguage(sourceLanguage);
                        if (isBlank(text)) {
                            status.error("No text found for language " + sourceLanguage + " at " + path + '/' + property);
                        }
                        if (isBlank(targetLanguage)) {
                            targetLanguage = model.getLanguageKey(); // pages default
                        }
                    }
                }
            }
            if (isBlank(targetLanguage)) {
                status.error("No targetLanguage given, and it could not be determined from context.");
            }
            if (status.isValid()) {
                String translation = null;
                List<String> cachekey = List.of(sourceLanguage, targetLanguage, text);
                String cached = translationCache.getIfPresent(cachekey);
                if (isNotBlank(cached)) {
                    LOG.info("Using cached result: {} -> {} - {} -> {}", sourceLanguage, targetLanguage, text, cached);
                    translation = cached;
                }
                if (!streaming && isBlank(translation)) {
                    translation = translationService.singleTranslation(text, sourceLanguage, targetLanguage, richtext);
                    translation = XSS.filter(translation);
                    // translationCache.put(cachekey, translation);
                }
                if (isNotBlank(translation)) {
                    status.data(RESULTKEY).put(RESULTKEY_TRANSLATION, List.of(translation));
                } else if (streaming) {
                    EventStream callback = new EventStream();
                    callback.addWholeResponseListener((result) -> {
                        // translationCache.put(cachekey, XSS.filter(result));
                    });
                    String id = saveStream(callback, request);
                    translationService.streamingSingleTranslation(text, sourceLanguage, targetLanguage, richtext, callback);
                    status.data(RESULTKEY).put(RESULTKEY_STREAMID, id);
                }
            }
        }

    }

    /**
     * Servlet representation of {@link GPTContentCreationService#generateKeywords(String)} with argument text.
     * Input parameters is text, output is in data.result.keywords a list of keywords.
     */
    public class KeywordsOperation extends AbstractGPTServletOperation {

        @Override
        protected void performOperation(Status status, SlingHttpServletRequest request, SlingHttpServletResponse response) {
            String text = status.getRequiredParameter(PARAMETER_TEXT, null, "No text given");
            if (status.isValid()) {
                List<String> result = contentCreationService.generateKeywords(text);
                result = result.stream().map(XSS::filter).collect(Collectors.toList());
                status.data(RESULTKEY).put(RESULTKEY_KEYWORDS, result);
            }
        }

    }

    /**
     * Servlet representation of {@link GPTContentCreationService#generateDescription(String, int)} with arguments text and maxwords.
     * Input parameters is text and the optional numeric parameter maxwords,
     * output is in data.result.description a string containing the description.
     */
    public class DescriptionOperation extends AbstractGPTServletOperation {

        @Override
        protected void performOperation(Status status, SlingHttpServletRequest request, SlingHttpServletResponse response) {
            String text = status.getRequiredParameter(PARAMETER_TEXT, null, "No text given");
            Integer maxwords = getOptionalInt(status, request, PARAMETER_MAXWORDS);
            if (status.isValid()) {
                String result = contentCreationService.generateDescription(text, maxwords != null ? maxwords : -1);
                result = XSS.filter(result);
                status.data(RESULTKEY).put(RESULTKEY_DESCRIPTIION, result);
            }
        }

    }

    /**
     * Servlet representation of {@link GPTContentCreationService#executePrompt(java.lang.String, int)}
     * with arguments prompt and maxwords.
     * Input parameters is text and the optional numeric parameter maxwords,
     * output is in data.result.text the generated text.
     */
    public class PromptOperation extends AbstractGPTServletOperation {

        @Override
        protected void performOperation(Status status, SlingHttpServletRequest request, SlingHttpServletResponse response) {
            String prompt = status.getRequiredParameter(PARAMETER_PROMPT, null, "No prompt given");
            Integer maxtokens = getOptionalInt(status, request, PARAMETER_MAXTOKENS);
            if (status.isValid()) {
                String result = contentCreationService.executePrompt(prompt, maxtokens != null ? maxtokens : -1);
                result = XSS.filter(result);
                status.data(RESULTKEY).put(RESULTKEY_TEXT, result);
            }
        }

    }

    /**
     * Servlet representation of {@link GPTContentCreationService#executePromptOnText(java.lang.String, java.lang.String, int)}
     * with arguments prompt, text and maxwords.
     * Input parameters is a text, the prompt to execute on it and the optional numeric parameter maxwords,
     * output is in data.result.text the generated text.
     */
    public class PromptOnTextOperation extends AbstractGPTServletOperation {

        @Override
        protected void performOperation(Status status, SlingHttpServletRequest request, SlingHttpServletResponse response) {
            String prompt = status.getRequiredParameter(PARAMETER_PROMPT, null, "No prompt given");
            String text = status.getRequiredParameter(PARAMETER_TEXT, null, "No text given");
            Integer maxtokens = getOptionalInt(status, request, PARAMETER_MAXTOKENS);
            if (status.isValid()) {
                String result = contentCreationService.executePromptOnText(prompt, text, maxtokens != null ? maxtokens : -1);
                result = XSS.filter(result);
                status.data(RESULTKEY).put(RESULTKEY_TEXT, result);
            }
        }

    }

    /**
     * Implements the content creation operation. Input parameters are:
     * - prompt: the prompt to execute
     * - textLength: the maximum length of the generated text. If it starts with a number and a | then the number is
     * used as maxwords parameter, limiting the number of tokens, and the rest is transmitted in the prompt to ChatGPT.
     * - inputPath: if a path is given, the markdown generated by the path is used as input
     * - inputText: alternatively to the path, this text is used as input
     * Output is in data.result.text the generated text.
     */
    public class CreateOperation extends AbstractGPTServletOperation {

        @Override
        protected void performOperation(Status status, SlingHttpServletRequest request, SlingHttpServletResponse response) {
            String prompt = status.getRequiredParameter(PARAMETER_PROMPT,
                    Pattern.compile("(?s).*\\S.*"), "No prompt given");
            boolean streaming = "true".equals(request.getParameter(PARAMETER_STREAMING));
            String textLength = request.getParameter("textLength");
            String inputPath = request.getParameter("inputPath");
            String inputText = request.getParameter("inputText");
            if (isNoneBlank(inputPath, inputText)) {
                status.error("Both inputPath and inputText given, only one of them is allowed");
            }
            boolean richtext = Boolean.TRUE.toString().equalsIgnoreCase(request.getParameter(PARAMETER_RICHTEXT));
            int maxtokens = 400; // some arbitrary default
            if (isNotBlank(textLength)) {
                Matcher matcher = Pattern.compile("\\s*(\\d+)\\s*\\|\\s*(.*)").matcher(textLength);
                if (matcher.matches()) {
                    maxtokens = Integer.parseInt(matcher.group(1));
                    textLength = matcher.group(2) + "\n\n" + prompt;
                }
            }
            if (status.isValid()) {
                String fullPrompt = prompt;
                if (isNotBlank(textLength)) {
                    fullPrompt = textLength + "\n\n" + prompt;
                }
                if (richtext) {
                    fullPrompt = fullPrompt + "\n\n" + "Create HTML and begin the text with <p>";
                }
                if (isNotBlank(inputPath)) {
                    Resource resource = request.getResourceResolver().getResource(inputPath);
                    if (resource == null) {
                        status.error("No resource found at " + inputPath);
                    } else {
                        inputText = markdownService.approximateMarkdown(resource);
                    }
                }
                if (status.isValid()) {
                    if (!streaming) {
                        String result;
                        if (isNotBlank(inputText)) {
                            result = contentCreationService.executePromptOnText(fullPrompt, inputText, maxtokens);
                        } else {
                            result = contentCreationService.executePrompt(fullPrompt, maxtokens);
                        }
                        result = XSS.filter(result);
                        status.data(RESULTKEY).put(RESULTKEY_TEXT, result);
                    } else {
                        EventStream callback = new EventStream();
                        String id = saveStream(callback, request);
                        if (isNotBlank(inputText)) {
                            contentCreationService.executePromptOnTextStreaming(fullPrompt, inputText, maxtokens, callback);
                        } else {
                            contentCreationService.executePromptStreaming(fullPrompt, maxtokens, callback);
                        }
                        status.data(RESULTKEY).put(RESULTKEY_STREAMID, id);
                    }
                }
            }
        }
    }

    /**
     * Returns an event stream that was prepared by a previous operation with parameter {@link #PARAMETER_STREAMING} set.
     * It got returned a {@link #RESULTKEY_STREAMID} key in the result data, and then retrieves the stream with this operation.
     * The event stream is stored in the session under the key {@link #SESSIONKEY_STREAMING} and is removed after
     * the request.
     */
    public class StreamResponseOperation implements ServletOperation {

        @Override
        public void doIt(@NotNull SlingHttpServletRequest request, @NotNull SlingHttpServletResponse response, @org.jetbrains.annotations.Nullable ResourceHandle resource)
                throws IOException {
            Status status = new Status(request, response, LOG);
            String streamId = status.getRequiredParameter(RESULTKEY_STREAMID, null, "No stream id given");
            if (status.isValid()) {
                EventStream stream = retrieveStream(streamId, request);
                if (stream == null) {
                    LOG.warn("No stream found for id {}", streamId); // the browser shouldn't ask for it
                    status.setStatus(410);
                } else {
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType("text/event-stream");
                    response.setHeader("Cache-Control", "no-cache");
                    try (PrintWriter writer = response.getWriter()) {
                        stream.writeTo(writer);
                        if (stream.getWholeResponse() != null) {
                            LOG.debug("Whole translation for {} : {}", streamId, stream.getWholeResponse());
                        }
                    } catch (IOException | InterruptedException e) {
                        status.error("Error writing to stream: " + e, e);
                        Thread.currentThread().interrupt();
                    }
                }
            }
            if (!status.isValid()) {
                status.sendJson(HttpServletResponse.SC_BAD_REQUEST);
            }
        }
    }

}