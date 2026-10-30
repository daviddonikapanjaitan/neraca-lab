package com.neracalab.backend.devcontext;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.devcontext.DevContextRepository.Entry;
import com.neracalab.backend.devcontext.DevContextRepository.Hit;
import com.neracalab.backend.devcontext.DevContextService.Request;

/**
 * The development context of Claude Code (RAG over {@code dev_context}): issues, fixes, data gaps, decisions and
 * procedures, saved while developing and read back by a later session.
 * <ul>
 *   <li>{@code POST /api/v1/dev-context} - save one ({@code kind}, {@code title}, {@code body} required)</li>
 *   <li>{@code PUT /api/v1/dev-context/{id}} - replace it (e.g. status OPEN -> RESOLVED, with what was done)</li>
 *   <li>{@code GET /api/v1/dev-context/{id}}, {@code DELETE /api/v1/dev-context/{id}}</li>
 *   <li>{@code GET /api/v1/dev-context?ticker=&kind=&status=&tag=&limit=} - list, newest first</li>
 *   <li>{@code GET /api/v1/dev-context/search?q=&ticker=&kind=&status=&tag=&limit=} - by meaning and keywords</li>
 *   <li>{@code POST /api/v1/dev-context/reembed} - embed the rows saved without an embedding</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/dev-context")
@RequiresPermission(Permission.INGESTION)
public class DevContextController {

    private final DevContextService service;

    public DevContextController(DevContextService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Entry create(@RequestBody Request request, AuthenticatedUser user) {
        return service.create(request, user == null ? "claude_code" : user.username());
    }

    @PutMapping("/{id}")
    public Entry update(@PathVariable long id, @RequestBody Request request) {
        return service.update(id, request);
    }

    @GetMapping("/{id}")
    public Entry get(@PathVariable long id) {
        return service.get(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    @GetMapping
    public List<Entry> list(@RequestParam(required = false) String ticker, @RequestParam(required = false) String kind,
                            @RequestParam(required = false) String status, @RequestParam(required = false) String tag,
                            @RequestParam(required = false) Integer limit) {
        return service.list(ticker, kind, status, tag, limit);
    }

    @GetMapping("/search")
    public List<Hit> search(@RequestParam String q, @RequestParam(required = false) String ticker,
                            @RequestParam(required = false) String kind, @RequestParam(required = false) String status,
                            @RequestParam(required = false) String tag, @RequestParam(required = false) Integer limit) {
        return service.search(q, ticker, kind, status, tag, limit);
    }

    @PostMapping("/reembed")
    public Map<String, Integer> reembed() {
        return Map.of("embedded", service.reembed());
    }
}
