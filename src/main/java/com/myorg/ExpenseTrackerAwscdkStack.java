package com.myorg;

import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.constructs.Construct;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;

import java.util.Arrays;
import java.util.stream.IntStream;
// import software.amazon.awscdk.Duration;
// import software.amazon.awscdk.services.sqs.Queue;

public class ExpenseTrackerAwscdkStack extends Stack {
    public ExpenseTrackerAwscdkStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public ExpenseTrackerAwscdkStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // The code that defines your stack goes here

        // example resource
        // final Queue queue = Queue.Builder.create(this, "ExpenseTrackerAwscdkQueue")
        //         .visibilityTimeout(Duration.seconds(300))
        //         .build();

        Vpc vpc = Vpc.Builder.create(this, "ExpenseTrackerVpc")
                .vpcName("ExpenseTrackerVpc")
                .maxAzs(2)
                .natGateways(2)
                .createInternetGateway(false)
                .subnetConfiguration(Arrays.asList(
                        SubnetConfiguration.builder()
                                .name("ExpenseTrackerPublicSubnet")
                                .subnetType(SubnetType.PUBLIC)
                                .cidrMask(24)
                                .build(),
                        SubnetConfiguration.builder()
                                .name("ExpenseTrackerPrivateSubnet")
                                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                                .cidrMask(24)
                                .build()
                ))
                .build();

        CfnInternetGateway internetGateway = CfnInternetGateway.Builder.create(this, "ExpenseTrackerInternetGateway")
                .build();

        CfnVPCGatewayAttachment vpcGatewayAttachment = CfnVPCGatewayAttachment.Builder.create(this, "ExpenseTrackerVPCGatewayAttachment")
                .vpcId(vpc.getVpcId())
                .internetGatewayId(internetGateway.getRef())
                .build();

        CfnEIP natGatewayOneEIP = CfnEIP.Builder.create(this, "ExpenseTrackerNatGatewayOneEIP").build();
        CfnEIP natGatewayTwoEIP = CfnEIP.Builder.create(this, "ExpenseTrackerNatGatewayTwoEIP").build();

        CfnNatGateway natGatewayOne = CfnNatGateway.Builder.create(this, "ExpenseTrackerNatGatewayOne")
                .allocationId(natGatewayOneEIP.getAttrAllocationId())
                .subnetId(vpc.getPublicSubnets().get(0).getSubnetId())
                .build();

        CfnNatGateway natGatewayTwo = CfnNatGateway.Builder.create(this, "ExpenseTrackerNatGatewayTwo")
                .allocationId(natGatewayTwoEIP.getAttrAllocationId())
                .subnetId(vpc.getPublicSubnets().get(1).getSubnetId())
                .build();

        IntStream.range(0, vpc.getPrivateSubnets().size()).forEach(index -> {
            String routeTableId = vpc.getPrivateSubnets().get(index).getRouteTable().getRouteTableId();
            new CfnRoute(this, "PrivateRouteToNatGateway-" + index, CfnRouteProps.builder()
                    .routeTableId(routeTableId)
                    .destinationCidrBlock("0.0.0.0/0")
                    .natGatewayId(index == 0 ? natGatewayOne.getRef() : natGatewayTwo.getRef())
                    .build());
        });

        IntStream.range(0, vpc.getPublicSubnets().size()).forEach(index -> {
            new CfnRoute(this, "PublicRouteToInternetGateway-" + index, CfnRouteProps.builder()
                    .routeTableId(vpc.getPublicSubnets().get(index).getRouteTable().getRouteTableId())
                    .destinationCidrBlock("0.0.0.0/0")
                    .gatewayId(internetGateway.getRef())
                    .build());
        });

        // Export VPC ID to SSM Parameter Store
        StringParameter.Builder.create(this, "VpcIdExport")
                .parameterName("VpcId")
                .stringValue(vpc.getVpcId())
                .build();

        IntStream.range(0, vpc.getPublicSubnets().size()).forEach(index -> {
            StringParameter.Builder.create(this, "PublicSubnetExport-" + index)
                    .parameterName("PublicSubnet-" + index)
                    .stringValue(vpc.getPublicSubnets().get(index).getSubnetId())
                    .build();
        });

        IntStream.range(0, vpc.getPrivateSubnets().size()).forEach(index -> {
            StringParameter.Builder.create(this, "PrivateSubnetExport-" + index)
                    .parameterName("PrivateSubnet-" + index)
                    .stringValue(vpc.getPrivateSubnets().get(index).getSubnetId())
                    .build();
        });


    }
}
