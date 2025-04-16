package group27proj3;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.Attribute;
import software.amazon.awssdk.services.rekognition.model.CompareFacesRequest;
import software.amazon.awssdk.services.rekognition.model.CompareFacesResponse;
import software.amazon.awssdk.services.rekognition.model.DetectFacesRequest;
import software.amazon.awssdk.services.rekognition.model.FaceDetail;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextRequest;
import software.amazon.awssdk.services.textract.model.Document;

public class ParticipationHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String DYNAMODB_TABLE = "ParticipationRecordsCdkProj3";
    private static final String S3_BUCKET_NAME = "proj3-group27-bucket-cdk";
    private static final String NAMES_IMAGE_PREFIX = "proj3/proj3-images/names/";
    private static final String FACE_IMAGES_PREFIX = "proj3/proj3-images/faces/";

    private final RekognitionClient rekognition = RekognitionClient.builder()
            .region(Region.US_EAST_2)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();

    private final TextractClient textract = TextractClient.builder()
            .region(Region.US_EAST_2)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();

    private final S3Client s3 = S3Client.builder()
            .region(Region.US_EAST_2)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();

    private final DynamoDbClient dynamoDB = DynamoDbClient.builder()
            .region(Region.US_EAST_2)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    // DTO for serializing FaceDetail
    private static class FaceDetailDTO {
        private Float confidence;
        private String gender;
        private Boolean smile;

        public FaceDetailDTO(FaceDetail faceDetail) {
            this.confidence = faceDetail.confidence();
            this.gender = faceDetail.gender() != null ? faceDetail.gender().toString() : null;
            this.smile = faceDetail.smile() != null ? faceDetail.smile().value() : null;
        }

        public Float getConfidence() {
            return confidence;
        }

        public void setConfidence(Float confidence) {
            this.confidence = confidence;
        }

        public String getGender() {
            return gender;
        }

        public void setGender(String gender) {
            this.gender = gender;
        }

        public Boolean getSmile() {
            return smile;
        }

        public void setSmile(Boolean smile) {
            this.smile = smile;
        }
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            if ("OPTIONS".equalsIgnoreCase(event.getHttpMethod())) {
                return createResponse(200, "{\"message\": \"CORS preflight successful\"}");
            }

            if (event.getBody() == null) {
                return errorResponse("Request body is empty", null, null, null);
            }

            JsonNode jsonNode = mapper.readTree(event.getBody());

            String name = jsonNode.has("name") ? jsonNode.get("name").asText().trim().toLowerCase() : null;
            String email = jsonNode.has("email") ? jsonNode.get("email").asText() : null;
            String classDate = jsonNode.has("class_date") ? jsonNode.get("class_date").asText() : null;
            String base64Image = jsonNode.has("uploaded_image_data") ? jsonNode.get("uploaded_image_data").asText() : null;
            String uploadedKey = jsonNode.has("uploaded_image_key") ? jsonNode.get("uploaded_image_key").asText() : null;

            if (name == null || email == null || classDate == null) {
                return errorResponse("Missing required fields: name, email, or class_date", name, email, classDate);
            }

            if (base64Image != null && !base64Image.isEmpty()) {
                try {
                    uploadedKey = uploadBase64Image(base64Image, name, email, classDate);
                    if (uploadedKey == null) {
                        return errorResponse("Failed to upload image to S3", name, email, classDate);
                    }
                } catch (Exception e) {
                    context.getLogger().log("Error uploading image to S3: " + e.getMessage());
                    return errorResponse("Failed to upload image to S3: " + e.getMessage(), name, email, classDate);
                }
            }

            if (uploadedKey == null) {
                return errorResponse("No uploaded image provided", name, email, classDate);
            }

            byte[] uploadedImage = getS3Object(uploadedKey);
            List<String> namesKeys = listS3Keys(NAMES_IMAGE_PREFIX);
            List<String> faceKeys = listS3Keys(FACE_IMAGES_PREFIX);

            if (uploadedImage == null || namesKeys.isEmpty() || faceKeys.isEmpty()) {
                return errorResponse("Failed to retrieve required images from S3", name, email, classDate);
            }

            List<List<String>> extractedNames = new ArrayList<>();
            ExecutorService executor = Executors.newFixedThreadPool(10);

            try {
                for (String key : namesKeys) {
                    byte[] image = getS3Object(key);
                    if (image != null) {
                        extractedNames.add(extractTextFromImage(image));
                    }
                }
            } finally {
                executor.shutdown();
            }

            List<FaceDetail> uploadedFaces = detectFaces(uploadedImage);
            List<FaceDetailDTO> uploadedFacesDTO = uploadedFaces.stream()
                    .map(FaceDetailDTO::new)
                    .collect(Collectors.toList());

            List<List<FaceDetailDTO>> referenceFaces = new ArrayList<>();
            for (String key : faceKeys) {
                byte[] refImg = getS3Object(key);
                if (refImg != null) {
                    List<FaceDetail> faces = detectFaces(refImg);
                    referenceFaces.add(faces.stream().map(FaceDetailDTO::new).collect(Collectors.toList()));
                } else {
                    referenceFaces.add(Collections.emptyList());
                }
            }

            if (uploadedFaces.isEmpty() || referenceFaces.stream().allMatch(List::isEmpty)) {
                return errorResponse("No faces detected", name, email, classDate);
            }

            List<Boolean> matches = new ArrayList<>();
            List<Float> scores = new ArrayList<>();
            for (String faceKey : faceKeys) {
                byte[] faceImage = getS3Object(faceKey);
                if (faceImage != null) {
                    CompareFacesResponse compare = compareFaces(uploadedImage, faceImage);
                    if (!compare.faceMatches().isEmpty()) {
                        matches.add(true);
                        scores.add(compare.faceMatches().get(0).similarity());
                    }
                }
            }

            boolean faceMatch = !matches.isEmpty();
            boolean nameMatch = extractedNames.stream()
                    .flatMap(Collection::stream)
                    .anyMatch(text -> text != null && text.toLowerCase().contains(name.toLowerCase()));

            boolean participation = faceMatch || nameMatch;

            boolean dynamoSuccess = writeToDynamoDB(name, email, classDate, participation, nameMatch, faceMatch, uploadedKey);

            ObjectNode response = mapper.createObjectNode();
            response.put("participation", participation);
            response.put("name", name);
            response.put("email", email);
            response.put("class_date", classDate);
            response.put("name_match", nameMatch);
            response.put("face_match", faceMatch);
            response.set("extracted_names", mapper.valueToTree(extractedNames));
            response.set("uploaded_faces", mapper.valueToTree(uploadedFacesDTO));
            response.set("reference_faces", mapper.valueToTree(referenceFaces));
            response.set("similarity_scores", mapper.valueToTree(scores));
            response.put("error", dynamoSuccess ? null : "Failed to write to DynamoDB");

            return createResponse(200, mapper.writeValueAsString(response));

        } catch (Exception e) {
            context.getLogger().log("Unexpected error: " + e.getMessage());
            ObjectNode err = mapper.createObjectNode();
            err.put("participation", false);
            err.put("error", "Unexpected error: " + e.getMessage());
            return createResponse(500, err.toString());
        }
    }

    private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withHeaders(Map.of(
                        "Content-Type", "application/json",
                        "Access-Control-Allow-Origin", "*",
                        "Access-Control-Allow-Methods", "OPTIONS,POST,GET",
                        "Access-Control-Allow-Headers", "Content-Type, Authorization, X-Amz-Date, X-Api-Key, X-Amz-Security-Token"
                ))
                .withBody(body);
    }

    private APIGatewayProxyResponseEvent errorResponse(String msg, String name, String email, String classDate) {
        ObjectNode res = mapper.createObjectNode();
        res.put("participation", false);
        res.put("name", name != null ? name : "");
        res.put("email", email != null ? email : "");
        res.put("class_date", classDate != null ? classDate : "");
        res.set("extracted_names", mapper.createArrayNode());
        res.put("name_match", false);
        res.put("face_match", false);
        res.set("uploaded_faces", mapper.createArrayNode());
        res.set("reference_faces", mapper.createArrayNode());
        res.set("similarity_scores", mapper.createArrayNode());
        res.put("error", msg);
        return createResponse(500, res.toString());
    }

    private String uploadBase64Image(String base64, String name, String email, String classDate) {
        try {
            if (base64.contains(",")) {
                base64 = base64.split(",")[1];
            }

            byte[] bytes = Base64.getDecoder().decode(base64);
            String key = String.format("proj3/proj3-images/uploads/%s/%s.jpg", classDate, name);

            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(S3_BUCKET_NAME)
                    .key(key)
                    .contentType("image/jpeg")
                    .build();

            s3.putObject(request, software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));
            return key;
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload image to S3", e);
        }
    }

    private byte[] getS3Object(String key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(S3_BUCKET_NAME)
                    .key(key)
                    .build();
            return s3.getObjectAsBytes(request).asByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> listS3Keys(String prefix) {
        try {
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(S3_BUCKET_NAME)
                    .prefix(prefix)
                    .build();
            return s3.listObjectsV2(request).contents().stream()
                    .map(software.amazon.awssdk.services.s3.model.S3Object::key)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<String> extractTextFromImage(byte[] imageBytes) {
        try {
            DetectDocumentTextRequest request = DetectDocumentTextRequest.builder()
                    .document(Document.builder().bytes(SdkBytes.fromByteArray(imageBytes)).build())
                    .build();

            return textract.detectDocumentText(request).blocks().stream()
                    .filter(b -> b.blockTypeAsString().equals("LINE"))
                    .map(Block::text)
                    .filter(text -> text != null)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<FaceDetail> detectFaces(byte[] image) {
        try {
            DetectFacesRequest request = DetectFacesRequest.builder()
                    .image(Image.builder().bytes(SdkBytes.fromByteArray(image)).build())
                    .attributes(Attribute.DEFAULT)
                    .build();

            return rekognition.detectFaces(request).faceDetails();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private CompareFacesResponse compareFaces(byte[] source, byte[] target) {
        try {
            CompareFacesRequest request = CompareFacesRequest.builder()
                    .sourceImage(Image.builder().bytes(SdkBytes.fromByteArray(source)).build())
                    .targetImage(Image.builder().bytes(SdkBytes.fromByteArray(target)).build())
                    .similarityThreshold(85f)
                    .build();

            return rekognition.compareFaces(request);
        } catch (Exception e) {
            return CompareFacesResponse.builder().faceMatches(Collections.emptyList()).build();
        }
    }

    private boolean writeToDynamoDB(String name, String email, String date, boolean participation, boolean nameMatch, boolean faceMatch, String imageKey) {
        try {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("name", AttributeValue.fromS(name));
            item.put("email", AttributeValue.fromS(email));
            item.put("class_date", AttributeValue.fromS(date));
            item.put("participation", AttributeValue.fromBool(participation));
            item.put("name_match", AttributeValue.fromBool(nameMatch));
            item.put("face_match", AttributeValue.fromBool(faceMatch));
            item.put("uploaded_image_key", AttributeValue.fromS(imageKey));

            PutItemRequest request = PutItemRequest.builder()
                    .tableName(DYNAMODB_TABLE)
                    .item(item)
                    .build();

            dynamoDB.putItem(request);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}