package com.jkxt.demo;

import com.jkxt.common.config.DeployProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Deployment-scoped, so background work cannot escape by losing its HTTP thread context. */
@Component
public final class DemoSideEffectPolicy {
  private final DeployProperties deploy;
  public DemoSideEffectPolicy(DeployProperties deploy){this.deploy=java.util.Objects.requireNonNull(deploy);}
  public boolean simulated(){return deploy.isDemo();}
  public void requireAllowed(String effect) {
    if(simulated() && !"local-notice".equals(effect))
      throw DemoWorkspaceRepository.error(HttpStatus.FORBIDDEN,"DEMO_SIDE_EFFECT_FORBIDDEN","演示环境禁止真实通知和外部收费服务");
  }
}
