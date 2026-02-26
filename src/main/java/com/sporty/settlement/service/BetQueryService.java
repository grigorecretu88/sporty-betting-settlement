package com.sporty.settlement.service;

import com.sporty.settlement.api.dto.BetResponse;
import com.sporty.settlement.repository.bet.BetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class BetQueryService {

    private final BetRepository betRepository;

    public BetQueryService(BetRepository betRepository) {
        this.betRepository = betRepository;
    }

    @Transactional(readOnly = true)
    public List<BetResponse> listBets() {
        return betRepository.findAllBy().stream()
                .map(BetResponse::from)
                .toList();
    }
}