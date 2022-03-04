The idea of the plugin was inspired by [IntelliJ behaviour](https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/execution/MavenJUnitPatcher.java) when it is running "junit" tests: if maven module defines some specific settings for maven-surefire plugin IntelliJ picks up those settings, resolves them and transforms to command line arguments, for example following configuration of maven-surefire plugin:

```xml
...
<groupId>org.apache.maven.plugins</groupId>
<artifactId>maven-surefire-plugin</artifactId>
<version>${surefire.version}</version>
<configuration>
    <systemPropertyVariables>
        <spring.datasource.url>jdbc:h2:mem:testdb</spring.datasource.url>
        <spring.datasource.driverClassName>spring.datasource.driverClassName</spring.datasource.driverClassName>
        <spring.datasource.username>sa</spring.datasource.username>
        <spring.datasource.password>password</spring.datasource.password>
    </systemPropertyVariables>
</configuration>
...
```

causes IntelliJ to run tests with `-Dspring.datasource.url=jdbc:h2:mem:testdb ...` system properties. 

This actually means that any developer may setup his own environment(s), define its parameters via maven profile and effectively switch between environments via choosing target maven profile when running "junit" tests.

Unfortunately, IntelliJ lacks the similar functionality for SpringBoot applications and the purpose of this plugin is to fill in this gap, the behaviour of this plugin is following: when IntelliJ runs main class, plugin tries to discover settings of `spring-boot-maven-plugin` with the `goal=run` and same `mainClass` and in case of success it enriches command line parameters with discovered system properties, for example, following configuration:

```xml
...
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-maven-plugin</artifactId>
<executions>
    <execution>
        <id>run-app</id>
        <goals>
            <goal>run</goal>
        </goals>
        <phase>none</phase>
        <configuration>
            <mainClass>com.tld.MainClass</mainClass>
            <systemPropertyVariables>
                <spring.datasource.url>jdbc:h2:mem:testdb</spring.datasource.url>
                <spring.datasource.driverClassName>spring.datasource.driverClassName</spring.datasource.driverClassName>
                <spring.datasource.username>sa</spring.datasource.username>
                <spring.datasource.password>password</spring.datasource.password>
            </systemPropertyVariables>
        </configuration>
    </execution>
</executions>
...
```

causes IntelliJ to run `com.tld.MainClass` with `-Dspring.datasource.url=jdbc:h2:mem:testdb ...` system properties. 
