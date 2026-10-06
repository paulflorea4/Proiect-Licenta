package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.SigninRequest;
import com.gradingplatform.backend.dto.SigninResponse;
import com.gradingplatform.backend.dto.SignupRequest;
import com.gradingplatform.backend.dto.UserResponse;
import com.gradingplatform.backend.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse signup(@Valid @RequestBody SignupRequest request) {
        return UserResponse.from(authService.signup(request));
    }

    @PostMapping("/signin")
    public SigninResponse signin(@Valid @RequestBody SigninRequest request) {
        AuthService.SigninResult result = authService.signin(request);
        return new SigninResponse(
                result.token().value(),
                SigninResponse.BEARER,
                result.token().validFor().toSeconds(),
                UserResponse.from(result.user()));
    }
}
