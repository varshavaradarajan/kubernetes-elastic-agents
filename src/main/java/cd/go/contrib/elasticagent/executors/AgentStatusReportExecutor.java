package cd.go.contrib.elasticagent.executors;

import cd.go.contrib.elasticagent.Constants;
import cd.go.contrib.elasticagent.KubernetesClientFactory;
import cd.go.contrib.elasticagent.PluginRequest;
import cd.go.contrib.elasticagent.builders.PluginStatusReportViewBuilder;
import cd.go.contrib.elasticagent.model.JobIdentifier;
import cd.go.contrib.elasticagent.model.reports.StatusReportGenerationErrorHandler;
import cd.go.contrib.elasticagent.model.reports.StatusReportGenerationException;
import cd.go.contrib.elasticagent.model.reports.agent.KubernetesElasticAgent;
import cd.go.contrib.elasticagent.requests.AgentStatusReportRequest;
import com.google.gson.JsonObject;
import com.thoughtworks.go.plugin.api.response.DefaultGoPluginApiResponse;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

import static cd.go.contrib.elasticagent.KubernetesPlugin.LOG;
import static java.text.MessageFormat.format;

public class AgentStatusReportExecutor {
    private final AgentStatusReportRequest request;
    private final PluginRequest pluginRequest;
    private final KubernetesClientFactory factory;
    private final PluginStatusReportViewBuilder statusReportViewBuilder;

    public AgentStatusReportExecutor(AgentStatusReportRequest request, PluginRequest pluginRequest) {
        this(request, pluginRequest, KubernetesClientFactory.instance(), PluginStatusReportViewBuilder.instance());
    }

    public AgentStatusReportExecutor(AgentStatusReportRequest request, PluginRequest pluginRequest, KubernetesClientFactory kubernetesClientFactory, PluginStatusReportViewBuilder builder) {
        this.request = request;
        this.pluginRequest = pluginRequest;
        this.factory = kubernetesClientFactory;
        this.statusReportViewBuilder = builder;
    }

    public GoPluginApiResponse execute() {
        String elasticAgentId = request.getElasticAgentId();
        JobIdentifier jobIdentifier = request.getJobIdentifier();
        LOG.info(format("[status-report] Generating status report for agent: {0} with job: {1}", elasticAgentId, jobIdentifier));
        KubernetesClient client = factory.client(pluginRequest.getPluginSettings());

        try {
            Pod pod;
            if (StringUtils.isNotBlank(elasticAgentId)) {
                pod = findPodUsingElasticAgentId(elasticAgentId, client);
            } else {
                pod = findPodUsingJobIdentifier(jobIdentifier, client);
            }

            KubernetesElasticAgent elasticAgent = KubernetesElasticAgent.fromPod(client, pod, jobIdentifier);

            final String statusReportView = statusReportViewBuilder.build(statusReportViewBuilder.getTemplate("agent-status-report.template.ftlh"), elasticAgent);

            final JsonObject responseJSON = new JsonObject();
            responseJSON.addProperty("view", statusReportView);

            return DefaultGoPluginApiResponse.success(responseJSON.toString());
        } catch (Exception e) {
            return StatusReportGenerationErrorHandler.handle(statusReportViewBuilder, e);
        }
    }

    private Pod findPodUsingJobIdentifier(JobIdentifier jobIdentifier, KubernetesClient client) {
        try {
            return client.pods()
                    .withLabel(Constants.JOB_ID_LABEL_KEY, String.valueOf(jobIdentifier.getJobId()))
                    .list().getItems().get(0);
        } catch (Exception e) {
            throw new StatusReportGenerationException(format("Can not find a running Pod for the provided job identifier: {0}.", jobIdentifier.representation()));
        }
    }

    private Pod findPodUsingElasticAgentId(String elasticAgentId, KubernetesClient client) {
        List<Pod> pods = client.pods().list().getItems();
        for (Pod pod : pods) {
            if (pod.getMetadata().getName().equals(elasticAgentId)) {
                return pod;
            }
        }

        throw new StatusReportGenerationException(format("Can not find a running Pod for the provided elastic agent id: {0}.", elasticAgentId));
    }
}
