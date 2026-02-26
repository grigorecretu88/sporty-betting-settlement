package com.sporty.settlement.api;

import com.sporty.settlement.api.dto.BetResponse;
import com.sporty.settlement.service.BetQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/bets")
public class BetsController {

    private final BetQueryService betQueryService;

    public BetsController(BetQueryService betQueryService) {
        this.betQueryService = betQueryService;
    }

    @GetMapping
    public List<BetResponse> list() {
        return betQueryService.listBets();
    }
}