package com.lingmao.scm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {

    /**
     * @Configuration + @Bean：意思是"Spring，启动时请调用这个方法，把返回的对象收进你的容器"。之后哪个类需要 PasswordEncoder，在构造器参数里写上它，Spring 就会把这个对象传进去。和 Mapper 被注入到 Service 里是同一个机制，只是 Mapper 由 MyBatis 生成，这个由我们自己 new
     * 为什么不在用到的地方直接 new BCryptPasswordEncoder()：今天有两处要用（登录、创建 admin），03 还要用。集中在一处，以后换算法只改这里。测试时也能把它换成假的
     * 返回类型写接口 PasswordEncoder，不写 BCryptPasswordEncoder：用的人只需要知道"能加密、能比对"，不需要知道背后是哪种算法。和 Controller 只认 Service 接口是同一个道理
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}