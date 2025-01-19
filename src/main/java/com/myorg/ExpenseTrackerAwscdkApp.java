package com.myorg;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

import java.util.Arrays;

public class ExpenseTrackerAwscdkApp {
    public static void main(final String[] args) {
        App app = new App();
        new ExpenseTrackerAwscdkStack(app, "ExpenseTrackerAwscdkStack", StackProps.builder()
                .build());

        new ExpenseServiceStack(app, "ExpenseServiceStack", StackProps.builder()
                .build());

        app.synth();
    }
}

