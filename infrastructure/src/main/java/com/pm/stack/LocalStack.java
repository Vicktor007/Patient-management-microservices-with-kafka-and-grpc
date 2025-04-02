package com.pm.stack;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.msk.CfnCluster;
import software.amazon.awscdk.services.rds.*;
import software.amazon.awscdk.services.route53.CfnHealthCheck;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LocalStack extends Stack {

    private final Vpc vpc;

    private final Cluster ecsCluster;

    public LocalStack(final App scope, String id, final StackProps props) {
        super(scope, id, props);

        this.vpc = createVpc();

        DatabaseInstance authServiceDB = createDatabase("AuthServiceDB", "auth-service-db");

        DatabaseInstance patientServiceDB = createDatabase("PatientServiceDB", "patient-service-db");

        CfnHealthCheck authDbHealthCheck =
                createHealthCheck(authServiceDB, "auth-db-health-check");

        CfnHealthCheck patientDbHealthCheck =
                createHealthCheck(patientServiceDB, "patient-service-db-health-check");

        CfnCluster mskCluster = createMskCluster();

        this.ecsCluster = createEcsCluster();

        FargateService authService =
                createFargateService("AuthService", "auth-service",
                        List.of(4005),
                        authServiceDB,
                        Map.of("JWT_SECRET", "Kj83NjlmOWp5a2BzUWZmOWtkNmRjZDUzYWJoYmMzYzE="));
        authService.getNode().addDependency(authDbHealthCheck);
        authService.getNode().addDependency(authServiceDB);

        FargateService billingService =
                createFargateService("BillingService",
                        "billing-service",
                        List.of(4001, 9001),
                        null,
                        null);

        FargateService analyticsService =
                createFargateService("AnalyticsService",
                        "analytics-service",
                        List.of(4002),
                        null,
                        null);

        analyticsService.getNode().addDependency(billingService);

        FargateService patientService =
                createFargateService("PatientService",
                        "patient-service",
                        List.of(4000),
                        patientServiceDB,
                        Map.of("BILLING_SERVICE_ADDRESS", "host.docker.internal",
                                "BILLING_SERVICE_PORT", "9001"));

        patientService.getNode().addDependency(patientServiceDB);
        patientService.getNode().addDependency(patientDbHealthCheck);
        patientService.getNode().addDependency(billingService);
        patientService.getNode().addDependency(mskCluster);

        createApiGatewayService();
    }

    private Vpc createVpc(){
      return  Vpc.Builder.create(this, "PatientManagementVPC").vpcName("PatientManagementVPC")
                .maxAzs(2)
                .build();
    }

    private DatabaseInstance createDatabase(String id, String dbName){
        return DatabaseInstance.Builder
                .create(this, id)
                .engine(DatabaseInstanceEngine.postgres(
                        PostgresInstanceEngineProps.builder()
                                .version(PostgresEngineVersion.VER_17_4).build()
                ))
                .vpc(vpc)
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE2, InstanceSize.MICRO))
                .allocatedStorage(20)
                .credentials(Credentials.fromGeneratedSecret("admin"))
                .databaseName(dbName)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
    }

    private CfnHealthCheck createHealthCheck(DatabaseInstance db, String id){
        return CfnHealthCheck.Builder.create(this, id)
                .healthCheckConfig(CfnHealthCheck.HealthCheckConfigProperty.builder()
                        .type("TCP")
                        .port(Token.asNumber(db.getDbInstanceEndpointPort()))
                        .ipAddress(db.getDbInstanceEndpointAddress())
                        .requestInterval(30)
                        .failureThreshold(3)
                        .build()
                ).build();
    }

    private CfnCluster createMskCluster(){
        return CfnCluster.Builder.create(this, "MskCluster")
                .clusterName("kafka-cluster")
                .kafkaVersion("2.8.0")
                .numberOfBrokerNodes(1)
                .brokerNodeGroupInfo(CfnCluster.BrokerNodeGroupInfoProperty.builder()
                        .instanceType("kafka.m5.xlarge")
                        .clientSubnets(vpc.getPrivateSubnets().stream()
                                .map(ISubnet::getSubnetId)
                                .collect(Collectors.toList()))
                        .brokerAzDistribution("DEFAULT").build()).build();
    }

    private Cluster createEcsCluster(){
        return Cluster.Builder.create(this, "PatientManagementCluster")
                .vpc(vpc)
                .defaultCloudMapNamespace(CloudMapNamespaceOptions.builder()
                        .name("patient-management.local").build())
                .build();
    }

    private FargateService createFargateService(String id, String imageName, List<Integer> ports, DatabaseInstance db, Map<String, String> additionalEnvs) {
        FargateTaskDefinition taskDefinition = FargateTaskDefinition.Builder.create(this, id + "Task")
                .cpu(256)
                .memoryLimitMiB(512)
                .build();
        ContainerDefinitionOptions.Builder containerDefinitionOptions =
                ContainerDefinitionOptions.builder().image(ContainerImage.fromRegistry(imageName))
                        .portMappings(ports.stream().map(port -> PortMapping.builder()
                                .containerPort(port).hostPort(port)
                                .protocol(Protocol.TCP).build())
                                .toList()).logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                                .logGroup(LogGroup.Builder.create(this, id + "LogGroup")
                                        .logGroupName("/ecs/" + imageName).removalPolicy(RemovalPolicy.DESTROY)
                                        .retention(RetentionDays.ONE_DAY)
                                        .build())
                                        .streamPrefix(imageName)
                                .build()));


        Map<String, String> additionalEnvsMap = new HashMap<>();

        additionalEnvsMap.put("SPRING_KAFKA_BOOTSTRAP_SERVERS","localhost.localstack.cloud:4510,localhost.localstack.cloud:4511, localhost.localstack.cloud:4512");

        if (additionalEnvs != null){
            additionalEnvsMap.putAll(additionalEnvs);
        }

        if (db != null){
            additionalEnvsMap.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://%s:%s/%s-db".formatted(db.getDbInstanceEndpointAddress(),
                    db.getDbInstanceEndpointPort(),
                    imageName));

            additionalEnvsMap.put("SPRING_DATASOURCE_USERNAME", "admin");
            additionalEnvsMap.put("SPRING_DATASOURCE_PASSWORD", db.getSecret().secretValueFromJson("password").toString());
            additionalEnvsMap.put("SPRING_JPA_HIBERNATE_DDL_AUTO", "update");
            additionalEnvsMap.put("SPRING_SQL_INIT_MODE", "always");
            additionalEnvsMap.put("SPRING_DATASOURCE_HIKARI_INITIALIZATION_FAIL_TIMEOUT", "60000");

        }
        containerDefinitionOptions.environment(additionalEnvsMap);
        taskDefinition.addContainer(imageName + "Container", containerDefinitionOptions.build());

        return FargateService.Builder.create(this, id)
                .cluster(ecsCluster)
                .taskDefinition(taskDefinition)
                .assignPublicIp(false)
                .serviceName(imageName)
                .build();
    }

    private void createApiGatewayService(){
        FargateTaskDefinition taskDefinition = FargateTaskDefinition.Builder.create(this, "ApiGatewayTaskDefinition")
                .cpu(256)
                .memoryLimitMiB(512)
                .build();

        ContainerDefinitionOptions containerDefinitionOptions =
                ContainerDefinitionOptions.builder().image(ContainerImage.fromRegistry("api-gateway"))
                        .environment(Map.of(
                                "SPRING_PROFILES_ACTIVE", "prod",
                                "AUTH_SERVICE_URL", "http://host.docker.internal:4005"
                        ))
                        .portMappings(List.of(4004).stream().map(port -> PortMapping.builder()
                                        .containerPort(port).hostPort(port)
                                        .protocol(Protocol.TCP).build())
                                .toList()).logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                                .logGroup(LogGroup.Builder.create(this,  "ApiGatewayLogGroup")
                                        .logGroupName("/ecs/api-gateway").removalPolicy(RemovalPolicy.DESTROY)
                                        .retention(RetentionDays.ONE_DAY)
                                        .build())
                                .streamPrefix("api-gateway")
                                .build()))
                        .build();

        taskDefinition.addContainer("APIGatewayContainer", containerDefinitionOptions);

        ApplicationLoadBalancedFargateService apiGateWay =
                ApplicationLoadBalancedFargateService.Builder.create(this, "APIGatewayService")
                        .cluster(ecsCluster)
                        .serviceName("api-gateway")
                        .taskDefinition(taskDefinition)
                        .desiredCount(1)
                        .healthCheckGracePeriod(Duration.seconds(60))
                        .build();


    }

    public static void main(final String[] args) {
        App app = new App(AppProps.builder().outdir("./infrastructure/cdk.out").build());

        StackProps stackProps = StackProps.builder().synthesizer(new BootstraplessSynthesizer()).build();
//        StackProps stackProps = StackProps.builder().synthesizer(new DefaultStackSynthesizer()).build();

        new LocalStack(app, "localstack", stackProps);

        System.out.println("Starting AWS CDK application...");
        try {
            app.synth();
            System.out.println("Synthesis successful!");
        } catch (Exception e) {
            System.err.println("Error during synthesis: " + e.getMessage());
            e.printStackTrace();
        }

    }

}
