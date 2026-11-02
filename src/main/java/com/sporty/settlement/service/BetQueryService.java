package com.sporty.settlement.service;

import com.sporty.settlement.api.dto.BetResponse;
import com.sporty.settlement.repository.bet.BetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BetQueryService {

    private final BetRepository betRepository;

    @Transactional(readOnly = true)
    public List<BetResponse> listBets() {
        return betRepository.findAll().stream()
                .map(BetResponse::from)
                .toList();
    }
}
