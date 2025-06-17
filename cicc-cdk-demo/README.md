# 📊 AmplifyParticipation — AWS CDK Java Project

**AmplifyParticipation** is an AWS Cloud Development Kit (CDK) project written in Java. It automates the deployment of a serverless participation verification system leveraging AWS Lambda, API Gateway, S3, DynamoDB, and related cloud services. This infrastructure enables document and image processing, face comparison, and result storage for scalable student or event participation tracking.

---

## 📁 Project Structure

```
AmplifyParticipation/
├── lambda/              # Lambda function source code (Java or other language)
├── src/                 # CDK application logic (Java)
├── cdk.json             # CDK entry point
├── pom.xml              # Maven configuration
└── README.md            # Project documentation
```

---

## ✅ Prerequisites

Ensure the following are installed and configured before running the project:

- Java 17
- [Apache Maven](https://maven.apache.org/)  
- [Node.js](https://nodejs.org/) and [AWS CDK CLI](https://docs.aws.amazon.com/cdk/latest/guide/work-with-cdk-java.html):  
  ```bash
  npm install -g aws-cdk
  ```
- AWS CLI configured:  
  ```bash
  aws configure
  ```

---

## 🚀 Setup & Deployment

### Step-by-Step Commands

1. **Clone or extract the project**

```bash
cd AmplifyParticipation
```

2. **Build the project with Maven**

```bash
mvn clean package
```

3. **Bootstrap your AWS environment (only needed once per region/account)**

```bash
cdk bootstrap
```

4. **List all available stacks**

```bash
cdk ls
```

5. **Synthesize the CloudFormation template**

```bash
cdk synth
```

6. **Deploy the stack to your AWS account**

```bash
cdk deploy
```

---

## 🔐 IAM Roles & Permissions

This project creates and uses the following AWS IAM roles:

- **Lambda execution role** — Grants permissions for:
  - Amazon S3 (GetObject, PutObject)
  - Amazon DynamoDB (PutItem)
  - Amazon Rekognition (CompareFaces, DetectFaces)
  - Amazon Textract (AnalyzeDocument, DetectDocumentText)
  - Logs: CreateLogGroup, CreateLogStream, PutLogEvents

These are defined within the CDK stack using `Role` and `PolicyStatement` constructs to ensure least-privilege access.

---

## 🔧 Useful CDK Commands

| Command        | Description                                           |
|----------------|-------------------------------------------------------|
| `cdk ls`       | List all stacks defined in the app                   |
| `cdk synth`    | Generate and output the CloudFormation template      |
| `cdk deploy`   | Deploy stack to AWS                                  |
| `cdk diff`     | Compare local state with deployed stack              |
| `cdk destroy`  | Delete deployed stack from AWS                       |
| `cdk bootstrap`| Prepare your AWS environment for CDK deployment      |

---

## 💡 Notes

- Update Lambda handler and infrastructure definitions inside `src/main/java/` and `lambda/` as needed.
- Environment variables, IAM policies, and permissions can be configured through CDK constructs in the source files.

---

---

## 👤 Author

**Aakash Reddy Nuthalapati**  
Graduate Student  
University of Central Oklahoma

- There is also and Makefile with all setup and deployment commands

## AWS LAMBDA FUNCTINALITY

In this project, AWS Lambda acted as the backend engine that automatically processed student participation data whenever an image was submitted. The Lambda function was written in Java and deployed using AWS CDK.

When a professor uploaded an image (either from an online Zoom session or an offline classroom), the frontend sent that image to an API Gateway endpoint, which triggered the Lambda function. Here’s what the Lambda function did step-by-step:

Image Upload to S3: It took the base64-encoded image from the request, decoded it, and uploaded it to a specific folder in an S3 bucket based on the class date and student name.

Text Extraction (Online Participation): For Zoom session images, the Lambda function used Amazon Textract to extract names from screenshots. It then checked if the uploaded student name was present in the extracted text.

Face Recognition (Offline Participation): For classroom images, the Lambda function used Amazon Rekognition to detect and compare faces in the uploaded image against stored reference face images in S3.

Participation Verification: If a match was found either through name recognition or face comparison, the system marked the student as “present.”

Data Storage in DynamoDB: Finally, the participation status along with metadata like name, email, date, match type, and image reference key was stored in a DynamoDB table.

By using Lambda, this entire process was automated without running a server, making the system cost-effective and highly scalable.
