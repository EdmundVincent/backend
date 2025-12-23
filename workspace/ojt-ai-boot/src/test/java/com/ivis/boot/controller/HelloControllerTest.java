package com.ivis.boot.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

/**
 * HelloController 単体テスト
 */
@ExtendWith(MockitoExtension.class)
class HelloControllerTest {

    @InjectMocks
    private HelloController helloController;

    @Test
    @DisplayName("hello エンドポイントが正しいメッセージを返す")
    void hello_ShouldReturnExpectedMessage() {
        // When
        var result = helloController.hello();

        // Then
        StepVerifier.create(result)
                .expectNextMatches(response -> 
                    response.getCode() == 200 &&
                    response.getData().contains("OJT-AI WebFlux")
                )
                .verifyComplete();
    }

    @Test
    @DisplayName("hello エンドポイントが空ではない")
    void hello_ShouldNotReturnEmpty() {
        // When
        var result = helloController.hello();

        // Then
        StepVerifier.create(result)
                .expectNextCount(1)
                .verifyComplete();
    }
}
