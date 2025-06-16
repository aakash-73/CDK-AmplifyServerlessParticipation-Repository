package Hackathon;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.CorsOptions;
import software.amazon.awscdk.services.apigateway.IntegrationResponse;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.LambdaRestApi;
import software.amazon.awscdk.services.apigateway.MethodOptions;
import software.amazon.awscdk.services.apigateway.MethodResponse;
import software.amazon.awscdk.services.apigateway.MockIntegration;
import software.amazon.awscdk.services.apigateway.Resource;
import software.amazon.awscdk.services.apigateway.StageOptions;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.constructs.Construct;

public class AmplifyParticipationDemoStack extends Stack {

    public AmplifyParticipationDemoStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public AmplifyParticipationDemoStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // The code that defines your stack goes here
        // example resource
        // final Queue queue = Queue.Builder.create(this, "AmplifyTextractDemoQueue")
        //         .visibilityTimeout(Duration.seconds(300))
        //         .build();
        // 1. Create Lambda function for processing images with Textract
        Function ParticipationFunction = Function.Builder.create(this, "Hackathon-proj3-ParticipationFunction")
                .runtime(Runtime.JAVA_17)
                .code(Code.fromAsset("./lambda/target/Participation.jar"))
                .handler("Hackathonproj3.ParticipationHandler::handleRequest")
                .memorySize(1024)
                .timeout(Duration.seconds(30))
                .build();

        // 2. Grant Lambda permissions to access Textract and S3
        // Grant S3 permissions (Get, Put)
        ParticipationFunction.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(Arrays.asList("s3:GetObject", "s3:PutObject"))
                .resources(Arrays.asList("arn:aws:s3:::proj3-Hackathon-bucket-cdk/*")) // Replace with your bucket name
                .build());

// Grant S3 ListBucket permission
        ParticipationFunction.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(Arrays.asList("s3:ListBucket"))
                .resources(Arrays.asList("arn:aws:s3:::proj3-Hackathon-bucket-cdk")) // Replace with your bucket name
                .build());

// Grant DynamoDB permissions (PutItem, GetItem)
        ParticipationFunction.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(Arrays.asList("dynamodb:PutItem", "dynamodb:GetItem"))
                .resources(Arrays.asList("arn:aws:dynamodb:us-east-2:343218204535:table/ParticipationRecordsCdkProj3")) // Replace region/account/table name
                .build());

// Grant Textract permissions
        ParticipationFunction.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(Arrays.asList("textract:DetectDocumentText", "textract:AnalyzeDocument"))
                .resources(Arrays.asList("*"))
                .build());

// Grant Rekognition permissions
        ParticipationFunction.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(Arrays.asList("rekognition:DetectFaces", "rekognition:CompareFaces"))
                .resources(Arrays.asList("*"))
                .build());

        // 3. Create API Gateway REST API
        LambdaRestApi api = LambdaRestApi.Builder.create(this, "proj3-ParticipationApi")
                .restApiName("proj3-ParticipationAPI")
                .description("API for processing images with AWS Textract & Rekognition")
                .handler(ParticipationFunction)
                .defaultCorsPreflightOptions(CorsOptions.builder()
                        .allowOrigins(Arrays.asList("*")) // For production, restrict to specific origins
                        .allowMethods(Arrays.asList("POST", "OPTIONS"))
                        .allowHeaders(Arrays.asList("Content-Type", "X-Amz-Date", "Authorization", "X-Api-Key"))
                        //  .allowCredentials(true)
                        //  .maxAge(Duration.hours(1))
                        .build())
                .deploy(true) // Ensure a deployment is created
                .deployOptions(StageOptions.builder()
                        .stageName("dev") // Define a stage name
                        .build())
                .build();
        // 4. Create API resources and methods
        // Create a 'process' resource
        Resource processResource = api.getRoot().addResource("process-image");

        // Create Lambda integration
        LambdaIntegration ParticipationIntegration = LambdaIntegration.Builder.create(ParticipationFunction)
                .proxy(true)
                .build();

        MockIntegration mockIntegration = MockIntegration.Builder.create()
                .requestTemplates(Map.of("application/json", "{'statusCode': 200}"))
                .integrationResponses(List.of(
                        IntegrationResponse.builder()
                                .statusCode("200")
                                .responseParameters(Map.of(
                                        "method.response.header.Access-Control-Allow-Origin", "'*'",
                                        "method.response.header.Access-Control-Allow-Headers", "'Content-Type,X-Amz-Date,Authorization,x-api-key'",
                                        "method.response.header.Access-Control-Allow-Methods", "'GET,POST,OPTIONS'"
                                ))
                                .build()
                ))
                .build();

        MethodResponse participationMethodResponse = MethodResponse.builder()
                .statusCode("200")
                .responseParameters(Map.of(
                        "method.response.header.Access-Control-Allow-Origin", false
                ))
                .build();
        MethodOptions participationMethodOptions = MethodOptions.builder()
                .methodResponses(List.of(participationMethodResponse))
                .build();

        // Add POST method to process resource
        processResource.addMethod("POST", ParticipationIntegration, participationMethodOptions);

        // try {
        //         MethodResponse optionsMethodResponse = MethodResponse.builder()
        //                 .statusCode("200")
        //                 .responseParameters(Map.of(
        //                     "method.response.header.Access-Control-Allow-Origin", false,
        //                     "method.response.header.Access-Control-Allow-Headers", false,
        //                     "method.response.header.Access-Control-Allow-Methods", false
        //                 ))
        //                 .build();
        //         MethodOptions optionsMethodOptions = MethodOptions.builder()
        //                 .methodResponses(List.of(optionsMethodResponse))
        //                 .build();
        //         processResource.addMethod("OPTIONS", mockIntegration, optionsMethodOptions);
        //     } catch (Exception e) {
        //         System.out.println("OPTIONS method already exists for the resource: " + e.getMessage());
        // }
        // 4. Set up Amplify App
        //  software.amazon.awscdk.services.amplify.App amplifyApp = software.amazon.awscdk.services.amplify.App.Builder
        //          .create(this, "TextractProcessingApp")
        //          .sourceCodeProvider(software.amazon.awscdk.services.amplify.GitHubSourceCodeProvider.Builder.create()
        //                  .owner("your-github-username")
        //                  .repository("your-repo-name")
        //                  .oauthToken(software.amazon.awscdk.core.SecretValue.secretsManager("github-token"))
        //                  .build())
        //          .buildSpec(BuildSpec.fromObjectToYaml(Map.of(
        //                  "version", "1.0",
        //                  "frontend", Map.of(
        //                          "phases", Map.of(
        //                                  "preBuild", Map.of(
        //                                          "commands", Arrays.asList("npm install")),
        //                                  "build", Map.of(
        //                                          "commands", Arrays.asList("npm run build"))),
        //                          "artifacts", Map.of(
        //                                  "baseDirectory", "build",
        //                                  "files", Arrays.asList("**/*")),
        //                          "cache", Map.of(
        //                                  "paths", Arrays.asList("node_modules/**/*"))))))
        //          .build();
        //  // 5. Add branch
        //  Branch masterBranch = Branch.Builder.create(this, "MasterBranch")
        //          .app(amplifyApp)
        //          .branchName("main")
        //          .build();
        //  // 6. Set environment variables for Amplify app
        //  Map<String, String> environmentVariables = new HashMap<>();
        //  environmentVariables.put("API_ENDPOINT", api.getUrl());
        //  masterBranch.addEnvironment(environmentVariables);
        // 7. Output the API endpoint URL and Amplify App URL
        String apiUrl = String.format("https://%s.execute-api.%s.amazonaws.com/%s/%s",
                api.getRestApiId(), // API Gateway ID
                Stack.of(this).getRegion(), // AWS Region
                "dev", // Stage name
                "process-image"); // Resource path
        CfnOutput.Builder.create(this, "ApiEndpoint")
                .description("API Gateway endpoint URL")
                .value(apiUrl)
                .build();
        //  CfnOutput.Builder.create(this, "ApiEndpoint")
        //          .description("API Gateway endpoint URL")
        //          .value(api.getUrl())
        //          .build();

        //  CfnOutput.Builder.create(this, "AmplifyAppURL")
        //          .description("URL of the Amplify application")
        //          .value(amplifyApp.getDefaultDomain())
        //          .build();
    }
}
