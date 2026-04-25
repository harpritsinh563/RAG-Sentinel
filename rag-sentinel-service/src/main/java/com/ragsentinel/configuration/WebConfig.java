package com.ragsentinel.configuration;

import com.ragsentinel.interceptor.PromptGuardInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static com.ragsentinel.constants.EndpointConstants.CHAT;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final PromptGuardInterceptor promptGuardInterceptor;

    public WebConfig(PromptGuardInterceptor promptGuardInterceptor){
        this.promptGuardInterceptor = promptGuardInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry interceptorRegistry){
        interceptorRegistry.addInterceptor(promptGuardInterceptor).addPathPatterns(CHAT);
    }


}
