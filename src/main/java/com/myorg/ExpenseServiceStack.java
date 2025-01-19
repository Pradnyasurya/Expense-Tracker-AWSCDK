package com.myorg;

import software.amazon.awscdk.Size;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.Protocol;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.constructs.Construct;

public class ExpenseServiceStack extends Stack {

    public ExpenseServiceStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public ExpenseServiceStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // The code that defines your stack goes here

        String vpcId = StringParameter.valueFromLookup(this, "VpcId");

        IVpc vpc = Vpc.fromLookup(this, "VpcImported", VpcLookupOptions.builder()
                .vpcId(vpcId)
                .build());

        ISubnet privateSubnet1 = Subnet.fromSubnetId(this, "PrivateSubnet1",
                StringParameter.valueFromLookup(this, "PrivateSubnet-0"));
        ISubnet privateSubnet2 = Subnet.fromSubnetId(this, "PrivateSubnet2",
                StringParameter.valueFromLookup(this, "PrivateSubnet-1"));

        SecurityGroup dbSecurityGroup = SecurityGroup.Builder.create(this, "DbSecurityGroup")
                .vpc(vpc)
                .allowAllOutbound(true)
                .build();

        dbSecurityGroup.addIngressRule(Peer.ipv4(vpc.getVpcCidrBlock()), Port.tcp(3306), "Allow MySQL traffic");
        dbSecurityGroup.addIngressRule(Peer.ipv4(vpc.getVpcCidrBlock()), Port.tcp(9092), "Allow Kafka traffic");
        dbSecurityGroup.addIngressRule(Peer.ipv4(vpc.getVpcCidrBlock()), Port.tcp(2181), "Allow Kafka to access Zookeeper");

        Cluster cluster = Cluster.Builder.create(this, "DatabaseKafkaCluster")
                .vpc(vpc)
                .defaultCloudMapNamespace(CloudMapNamespaceOptions.builder()
                        .name("local")
                        .build())
                .build();

        NetworkLoadBalancer nlb = NetworkLoadBalancer.Builder.create(this, "DatabaseNLB")
                .vpc(vpc)
                .internetFacing(false)
                .vpcSubnets(SubnetSelection.builder()
                        .subnets(java.util.List.of(privateSubnet1, privateSubnet2))
                        .build())
                .build();

        FargateTaskDefinition mysqlTaskDefinition = FargateTaskDefinition.Builder.create(this, "MySQLTaskDef").build();

        mysqlTaskDefinition.addContainer("MySQLContainer", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry("mysql:8.3.0"))
                .environment(java.util.Map.of(
                        "MYSQL_ROOT_PASSWORD", "password",
                        "MYSQL_USER", "user",
                        "MYSQL_PASSWORD", "password",
                        "MYSQL_ROOT_USER", "root"))
                .logging(LogDrivers.awsLogs(AwsLogDriverProps.builder()
                        .streamPrefix("MySql")
                        .mode(AwsLogDriverMode.NON_BLOCKING)
                        .maxBufferSize(Size.mebibytes(25))
                        .build()))
                .portMappings(java.util.List.of(PortMapping.builder()
                        .containerPort(3306)
                        .build()))
                .build());

        FargateTaskDefinition zookeeperTaskDefinition = FargateTaskDefinition.Builder.create(this, "ZookeeperTaskDef")
                .memoryLimitMiB(512)
                .cpu(256)
                .build();

        zookeeperTaskDefinition.addContainer("ZookeeperContainer", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry("confluentinc/cp-zookeeper:7.4.4"))
                .environment(java.util.Map.of(
                        "ZOOKEEPER_CLIENT_PORT", "2181",
                        "ZOOKEEPER_TICK_TIME", "2000"))
                .portMappings(java.util.List.of(PortMapping.builder()
                        .containerPort(2181)
                        .build()))
                .logging(LogDrivers.awsLogs(AwsLogDriverProps.builder()
                        .streamPrefix("Zookeeper")
                        .mode(AwsLogDriverMode.NON_BLOCKING)
                        .maxBufferSize(Size.mebibytes(25))
                        .build()))
                .build());

        FargateTaskDefinition kafkaTaskDefinition = FargateTaskDefinition.Builder.create(this, "KafkaTaskDef")
                .memoryLimitMiB(1024)
                .cpu(512)
                .build();

        kafkaTaskDefinition.addContainer("KafkaContainer", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry("confluentinc/cp-kafka:7.4.4"))
                .environment(java.util.Map.of(
                        "KAFKA_BROKER_ID", "1",
                        "KAFKA_ZOOKEEPER_CONNECT", "zookeeper-service.local:2181",
                        "KAFKA_ADVERTISED_LISTENERS", String.format("PLAINTEXT://%s:9092", nlb.getLoadBalancerDnsName()),
                        "KAFKA_LISTENERS", "PLAINTEXT://:9092",
                        "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "PLAINTEXT:PLAINTEXT",
                        "KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT",
                        "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "3"))
                .portMappings(java.util.List.of(PortMapping.builder()
                        .containerPort(9092)
                        .build()))
                .logging(LogDrivers.awsLogs(AwsLogDriverProps.builder()
                        .streamPrefix("Kafka")
                        .mode(AwsLogDriverMode.NON_BLOCKING)
                        .maxBufferSize(Size.mebibytes(25))
                        .build()))
                .build());

        FargateService mysqlService = FargateService.Builder.create(this, "MySQLService")
                .cluster(cluster)
                .taskDefinition(mysqlTaskDefinition)
                .desiredCount(1)
                .securityGroups(java.util.List.of(dbSecurityGroup))
                .vpcSubnets(SubnetSelection.builder()
                        .subnets(java.util.List.of(privateSubnet1, privateSubnet2))
                        .build())
                .build();

        FargateService zookeeperService = FargateService.Builder.create(this, "ZookeeperService")
                .cluster(cluster)
                .taskDefinition(zookeeperTaskDefinition)
                .desiredCount(3)
                .securityGroups(java.util.List.of(dbSecurityGroup))
                .vpcSubnets(SubnetSelection.builder()
                        .subnets(java.util.List.of(privateSubnet1, privateSubnet2))
                        .build())
                .cloudMapOptions(CloudMapOptions.builder()
                        .name("zookeeper-service")
                        .build())
                .build();

        FargateService kafkaService = FargateService.Builder.create(this, "KafkaService")
                .cluster(cluster)
                .taskDefinition(kafkaTaskDefinition)
                .desiredCount(3)
                .securityGroups(java.util.List.of(dbSecurityGroup))
                .vpcSubnets(SubnetSelection.builder()
                        .subnets(java.util.List.of(privateSubnet1, privateSubnet2))
                        .build())
                .build();

        NetworkTargetGroup mysqlTargetGroup = NetworkTargetGroup.Builder.create(this, "MySQLTargetGroup")
                .vpc(vpc)
                .port(3306)
                .protocol(Protocol.TCP)
                .targetType(TargetType.IP)
                .build();

        NetworkTargetGroup kafkaTargetGroup = NetworkTargetGroup.Builder.create(this, "KafkaTargetGroup")
                .vpc(vpc)
                .port(9092)
                .protocol(Protocol.TCP)
                .targetType(TargetType.IP)
                .build();

        mysqlTargetGroup.addTarget(mysqlService);
        kafkaTargetGroup.addTarget(kafkaService);

        nlb.addListener("MySQLListener", BaseNetworkListenerProps.builder()
                .port(3306)
                .protocol(Protocol.TCP)
                .defaultTargetGroups(java.util.List.of(mysqlTargetGroup))
                .build());

        nlb.addListener("KafkaListener", BaseNetworkListenerProps.builder()
                .port(9092)
                .protocol(Protocol.TCP)
                .defaultTargetGroups(java.util.List.of(kafkaTargetGroup))
                .build());

        StringParameter.Builder.create(this, "ExpenseTrackerServicesNLB")
                .parameterName("ExpenseTrackerServicesNLB")
                .stringValue(nlb.getLoadBalancerDnsName())
                .build();


    }
}
