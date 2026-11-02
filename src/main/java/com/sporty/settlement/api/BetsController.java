package com.sporty.settlement.api;

import com.sporty.settlement.api.dto.BetResponse;
import com.sporty.settlement.service.BetQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/bets")
@RequiredArgsConstructor
public class BetsController {

    private final BetQueryService betQueryService;

    @GetMapping
    public List<BetResponse> list() {
        return betQueryService.listBets();
    }
}
