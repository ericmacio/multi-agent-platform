package com.cognizant.emk.multiagent.infrastructure.web.agent;

import com.cognizant.emk.multiagent.application.agent.CreateAgentUseCase;
import com.cognizant.emk.multiagent.application.agent.CreateAgentUseCase.CreateAgentCommand;
import com.cognizant.emk.multiagent.application.agent.DeleteAgentUseCase;
import com.cognizant.emk.multiagent.application.agent.DeleteAgentUseCase.DeleteAgentCommand;
import com.cognizant.emk.multiagent.application.agent.GetAgentUseCase;
import com.cognizant.emk.multiagent.application.agent.GetAgentUseCase.GetAgentQuery;
import com.cognizant.emk.multiagent.application.agent.ListAgentsUseCase;
import com.cognizant.emk.multiagent.application.agent.ListAgentsUseCase.ListAgentsQuery;
import com.cognizant.emk.multiagent.application.agent.UpdateAgentUseCase;
import com.cognizant.emk.multiagent.application.agent.UpdateAgentUseCase.UpdateAgentCommand;
import com.cognizant.emk.multiagent.application.shared.PageSize;
import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.agent.SamplingParams;
import com.cognizant.emk.multiagent.domain.agent.Team;
import com.cognizant.emk.multiagent.domain.auth.UserPrincipal;
import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import com.cognizant.emk.multiagent.infrastructure.web.pagination.CursorCodec;
import com.cognizant.emk.multiagent.infrastructure.web.pagination.PageDto;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST adapter for the agent CRUD endpoints (REQ-AGT-006 / -007, design §6.2.7).
 *
 * <p>Class-level {@code @PreAuthorize} is not needed here — the URL guard
 * {@code /api/v1/agents/** → hasAnyRole("STANDARD", "ADMIN")} introduced in
 * {@code SpringSecurityConfig} already excludes SYSTEM. The principal is
 * resolved with {@link AuthenticationPrincipal} {@link UserPrincipal}: any
 * SYSTEM caller would be 403-ed before this controller runs, so the type is
 * always {@code UserPrincipal} here.
 *
 * <p>No class-level {@code @RequestMapping}: the {@code /api/v1} prefix is
 * applied centrally by {@code WebConfig}.
 */
@RestController
public class AgentsController {

    private final CreateAgentUseCase createAgentUseCase;
    private final ListAgentsUseCase listAgentsUseCase;
    private final GetAgentUseCase getAgentUseCase;
    private final UpdateAgentUseCase updateAgentUseCase;
    private final DeleteAgentUseCase deleteAgentUseCase;
    private final CursorCodec cursorCodec;

    public AgentsController(
            CreateAgentUseCase createAgentUseCase,
            ListAgentsUseCase listAgentsUseCase,
            GetAgentUseCase getAgentUseCase,
            UpdateAgentUseCase updateAgentUseCase,
            DeleteAgentUseCase deleteAgentUseCase,
            CursorCodec cursorCodec) {
        this.createAgentUseCase = createAgentUseCase;
        this.listAgentsUseCase = listAgentsUseCase;
        this.getAgentUseCase = getAgentUseCase;
        this.updateAgentUseCase = updateAgentUseCase;
        this.deleteAgentUseCase = deleteAgentUseCase;
        this.cursorCodec = cursorCodec;
    }

    // ------- US-06-004: POST /agents -------

    @PostMapping("/agents")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentResponse create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody AgentRequest request) {
        Agent created = createAgentUseCase.create(new CreateAgentCommand(
                principal.id(),
                new AgentName(request.name()),
                request.description(),
                request.systemPrompt(),
                resolveMemorySize(request.memorySize()),
                new SamplingParams(
                        request.llmModel(),
                        request.temperature(),
                        request.maxOutputTokens(),
                        request.topP()),
                nullToEmptyStringList(request.tools()),
                nullToEmptyStringList(request.enabledMcpServers()),
                buildTeam(request.team())));
        return AgentResponseMapper.toResponse(created);
    }

    // ------- US-06-005: GET /agents -------

    @GetMapping("/agents")
    public PageDto<AgentResponse> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "pageSize", required = false) Integer pageSize) {
        Cursor decoded = cursorCodec.decode(cursor);
        PageSize ps = PageSize.fromQueryParam(pageSize);
        Page<Agent> page = listAgentsUseCase.list(
                new ListAgentsQuery(principal.id(), decoded, ps));
        return PageDto.of(page, cursorCodec, AgentResponseMapper::toResponse);
    }

    // ------- US-06-006: GET /agents/{agentId} -------

    @GetMapping("/agents/{agentId}")
    public AgentResponse get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("agentId") UUID agentId) {
        Agent agent = getAgentUseCase.get(
                new GetAgentQuery(principal.id(), new AgentId(agentId)));
        return AgentResponseMapper.toResponse(agent);
    }

    // ------- US-06-007: PUT /agents/{agentId} -------

    @PutMapping("/agents/{agentId}")
    public AgentResponse replace(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("agentId") UUID agentId,
            @Valid @RequestBody AgentRequest request) {
        Agent updated = updateAgentUseCase.replace(new UpdateAgentCommand(
                principal.id(),
                new AgentId(agentId),
                new AgentName(request.name()),
                request.description(),
                request.systemPrompt(),
                resolveMemorySize(request.memorySize()),
                new SamplingParams(
                        request.llmModel(),
                        request.temperature(),
                        request.maxOutputTokens(),
                        request.topP()),
                nullToEmptyStringList(request.tools()),
                nullToEmptyStringList(request.enabledMcpServers()),
                buildTeam(request.team())));
        return AgentResponseMapper.toResponse(updated);
    }

    // ------- US-06-008: DELETE /agents/{agentId} -------

    @DeleteMapping("/agents/{agentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("agentId") UUID agentId) {
        deleteAgentUseCase.delete(new DeleteAgentCommand(
                principal.id(), new AgentId(agentId)));
    }

    // ------- helpers -------

    private static MemorySize resolveMemorySize(Integer requested) {
        return requested == null ? MemorySize.DEFAULT : new MemorySize(requested);
    }

    private static List<String> nullToEmptyStringList(List<String> raw) {
        return raw == null ? List.of() : raw;
    }

    private static Team buildTeam(List<UUID> raw) {
        if (raw == null || raw.isEmpty()) {
            return Team.EMPTY;
        }
        return new Team(raw.stream().map(AgentId::new).toList());
    }
}
