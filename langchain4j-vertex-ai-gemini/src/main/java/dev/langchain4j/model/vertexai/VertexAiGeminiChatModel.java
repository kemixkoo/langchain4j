package dev.langchain4j.model.vertexai;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.*;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.ModelConstant;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.vertexai.spi.VertexAiGeminiChatModelBuilderFactory;
import lombok.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static dev.langchain4j.internal.RetryUtils.withRetry;
import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.ValidationUtils.ensureNotBlank;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;
import static dev.langchain4j.spi.ServiceHelper.loadFactories;

/**
 * Represents a Google Vertex AI Gemini language model with a chat completion interface, such as gemini-pro.
 * See details <a href="https://cloud.google.com/vertex-ai/docs/generative-ai/model-reference/gemini">here</a>.
 * <br>
 * Please follow these steps before using this model:
 * <br>
 * 1. <a href="https://github.com/googleapis/java-aiplatform?tab=readme-ov-file#authentication">Authentication</a>
 * <br>
 * When developing locally, you can use one of:
 * <br>
 * a) <a href="https://github.com/googleapis/google-cloud-java?tab=readme-ov-file#local-developmenttesting">Google Cloud SDK</a>
 * <br>
 * b) <a href="https://github.com/googleapis/google-cloud-java?tab=readme-ov-file#using-a-service-account-recommended">Service account</a>
 * When using service account, ensure that <code>GOOGLE_APPLICATION_CREDENTIALS</code> environment variable points to your JSON service account key.
 * <br>
 * 2. <a href="https://github.com/googleapis/java-aiplatform?tab=readme-ov-file#authorization">Authorization</a>
 * <br>
 * 3. <a href="https://github.com/googleapis/java-aiplatform?tab=readme-ov-file#prerequisites">Prerequisites</a>
 */
public class VertexAiGeminiChatModel implements ChatLanguageModel, Closeable {

    private final GenerativeModel generativeModel;
    private final GenerationConfig generationConfig;
    private final Integer maxRetries;
    private final VertexAI vertexAI;

    private final Map<HarmCategory, SafetyThreshold> safetySettings;

    private final Tool googleSearch;
    private final Tool vertexSearch;

    private final ToolConfig toolConfig;
    private final List<String> allowedFunctionNames;

    private final boolean logRequests;
    private final boolean logResponses;
    private static final Logger logger = LoggerFactory.getLogger(VertexAiGeminiChatModel.class);

    @Builder
    public VertexAiGeminiChatModel(String project,
                                   String location,
                                   String modelName,
                                   Float temperature,
                                   Integer maxOutputTokens,
                                   Integer topK,
                                   Float topP,
                                   Integer maxRetries,
                                   String responseMimeType,
                                   Schema responseSchema,
                                   Map<HarmCategory, SafetyThreshold> safetySettings,
                                   Boolean useGoogleSearch,
                                   String vertexSearchDatastore,
                                   ToolCallingMode toolCallingMode,
                                   List<String> allowedFunctionNames,
                                   Boolean logRequests,
                                   Boolean logResponses) {
        GenerationConfig.Builder generationConfigBuilder = GenerationConfig.newBuilder();
        if (temperature != null) {
            generationConfigBuilder.setTemperature(temperature);
        }
        if (maxOutputTokens != null) {
            generationConfigBuilder.setMaxOutputTokens(maxOutputTokens);
        }
        if (topK != null) {
            generationConfigBuilder.setTopK(topK);
        }
        if (topP != null) {
            generationConfigBuilder.setTopP(topP);
        }
        if (responseMimeType != null) {
            generationConfigBuilder.setResponseMimeType(responseMimeType);
        }
        if (responseSchema != null) {
            generationConfigBuilder.setResponseMimeType("application/json");
            generationConfigBuilder.setResponseSchema(responseSchema);
        }
        this.generationConfig = generationConfigBuilder.build();

        if (safetySettings != null) {
            this.safetySettings = new HashMap<>(safetySettings);
        } else {
            this.safetySettings = Collections.emptyMap();
        }

        if (useGoogleSearch != null && useGoogleSearch) {
            googleSearch = ResponseGrounding.googleSearchTool();
        } else {
            googleSearch = null;
        }
        if (vertexSearchDatastore != null) {
            vertexSearch = ResponseGrounding.vertexAiSearch(vertexSearchDatastore);
        } else {
            vertexSearch = null;
        }

        if (allowedFunctionNames != null) {
            this.allowedFunctionNames = Collections.unmodifiableList(allowedFunctionNames);
        } else {
            this.allowedFunctionNames = Collections.emptyList();
        }
        if (toolCallingMode != null) {
            // only a subset of functions allowed to be used by the model
            if (toolCallingMode == ToolCallingMode.ANY &&
                allowedFunctionNames != null && !allowedFunctionNames.isEmpty()) {
                this.toolConfig = ToolConfig.newBuilder().setFunctionCallingConfig(
                    FunctionCallingConfig.newBuilder()
                        .setMode(FunctionCallingConfig.Mode.ANY)
                        .addAllAllowedFunctionNames(this.allowedFunctionNames)
                        .build()
                ).build();
            } else if (toolCallingMode == ToolCallingMode.NONE) { // no functions allowed
                this.toolConfig = ToolConfig.newBuilder().setFunctionCallingConfig(
                    FunctionCallingConfig.newBuilder()
                        .setMode(FunctionCallingConfig.Mode.NONE)
                        .build()
                ).build();
            } else { // Mode AUTO by default
                this.toolConfig = ToolConfig.newBuilder().setFunctionCallingConfig(
                    FunctionCallingConfig.newBuilder()
                        .setMode(FunctionCallingConfig.Mode.AUTO)
                        .build()
                ).build();
            }
        } else {
            this.toolConfig = ToolConfig.newBuilder().setFunctionCallingConfig(
                FunctionCallingConfig.newBuilder()
                    .setMode(FunctionCallingConfig.Mode.AUTO)
                    .build()
            ).build();
        }

        this.vertexAI = new VertexAI.Builder()
            .setProjectId(ensureNotBlank(project, "project"))
            .setLocation(ensureNotBlank(location, "location"))
            .setCustomHeaders(Collections.singletonMap("user-agent", "LangChain4j"))
            .build();

        this.generativeModel = new GenerativeModel(
            ensureNotBlank(modelName, "modelName"), vertexAI)
            .withGenerationConfig(generationConfig);

        this.maxRetries = getOrDefault(maxRetries, ModelConstant.DEFAULT_CLIENT_RETRIES);

        this.logRequests = getOrDefault(logRequests, false);
        this.logResponses = getOrDefault(logResponses, false);
    }

    public VertexAiGeminiChatModel(GenerativeModel generativeModel,
                                   GenerationConfig generationConfig) {
        this(generativeModel, generationConfig, 3);
    }

    public VertexAiGeminiChatModel(GenerativeModel generativeModel,
                                   GenerationConfig generationConfig,
                                   Integer maxRetries) {
        this.generationConfig = ensureNotNull(generationConfig, "generationConfig");
        this.generativeModel = ensureNotNull(generativeModel, "generativeModel")
            .withGenerationConfig(generationConfig);
        this.maxRetries = getOrDefault(maxRetries, ModelConstant.DEFAULT_CLIENT_RETRIES);
        this.vertexAI = null;
        this.safetySettings = Collections.emptyMap();
        this.googleSearch = null;
        this.vertexSearch = null;
        this.toolConfig = ToolConfig.newBuilder().setFunctionCallingConfig(
            FunctionCallingConfig.newBuilder()
                .setMode(FunctionCallingConfig.Mode.AUTO)
                .build()
        ).build();
        this.allowedFunctionNames = Collections.emptyList();
        this.logRequests = false;
        this.logResponses = false;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(messages, new ArrayList<>());
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        String modelName = generativeModel.getModelName();

        List<Tool> tools = new ArrayList<>();
        if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
            Tool tool = FunctionCallHelper.convertToolSpecifications(toolSpecifications);
            tools.add(tool);
        }

        if (this.googleSearch != null) {
            tools.add(this.googleSearch);
        }
        if (this.vertexSearch != null) {
            tools.add(this.vertexSearch);
        }

        GenerativeModel model = this.generativeModel
            .withTools(tools)
            .withToolConfig(this.toolConfig);

        ContentsMapper.InstructionAndContent instructionAndContent =
            ContentsMapper.splitInstructionAndContent(messages);

        if (instructionAndContent.systemInstruction != null) {
            model = model.withSystemInstruction(instructionAndContent.systemInstruction);
        }

        if (!this.safetySettings.isEmpty()) {
            model = model.withSafetySettings(SafetySettingsMapper.mapSafetySettings(this.safetySettings));
        }

        if (this.logRequests && logger.isDebugEnabled()) {
            logger.debug("GEMINI ({}) request: {} tools: {}", modelName, instructionAndContent, tools);
        }

        GenerativeModel finalModel = model;
        GenerateContentResponse response = withRetry(() ->
            finalModel.generateContent(instructionAndContent.contents), maxRetries);

        if (this.logResponses && logger.isDebugEnabled()) {
            logger.debug("GEMINI ({}) response: {}", modelName, response);
        }

        Content content = ResponseHandler.getContent(response);

        List<FunctionCall> functionCalls = content.getPartsList().stream()
            .filter(Part::hasFunctionCall)
            .map(Part::getFunctionCall)
            .collect(Collectors.toList());

        if (!functionCalls.isEmpty()) {
            List<ToolExecutionRequest> toolExecutionRequests = FunctionCallHelper.fromFunctionCalls(functionCalls);

            return Response.from(
                AiMessage.from(toolExecutionRequests),
                TokenUsageMapper.map(response.getUsageMetadata()),
                FinishReasonMapper.map(ResponseHandler.getFinishReason(response))
            );
        } else {
            return Response.from(
                AiMessage.from(ResponseHandler.getText(response)),
                TokenUsageMapper.map(response.getUsageMetadata()),
                FinishReasonMapper.map(ResponseHandler.getFinishReason(response))
            );
        }
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
        if (toolSpecification == null) {
            return generate(messages);
        } else {
            return generate(messages, Collections.singletonList(toolSpecification));
        }
    }

    @Override
    public void close() throws IOException {
        if (this.vertexAI != null) {
            vertexAI.close();
        }
    }

    public static VertexAiGeminiChatModelBuilder builder() {
        for (VertexAiGeminiChatModelBuilderFactory factory : loadFactories(VertexAiGeminiChatModelBuilderFactory.class)) {
            return factory.get();
        }
        return new VertexAiGeminiChatModelBuilder();
    }

    public static class VertexAiGeminiChatModelBuilder {
        public VertexAiGeminiChatModelBuilder() {
            // This is public so it can be extended
            // By default with Lombok it becomes package private
        }
    }
}