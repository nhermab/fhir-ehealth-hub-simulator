# Belgian eHealth Interhub FHIR Simulator (`fhir-ehealth-hub-simulator`)

> **Reference Server Implementation** for the **Belgian Federated eHealth Interhub FHIR Specification**, modernizing legacy KMEHR/SOAP transactions (`getTransactionList` and `getTransaction`) into **IHE MHD (Mobile access to Health Documents) on HL7® FHIR® R4**.

[![FHIR R4](https://img.shields.io/badge/FHIR-R4%20(v4.0.1)-orange.svg)](http://hl7.org/fhir/R4/)
[![IHE MHD](https://img.shields.io/badge/IHE%20MHD-v4.2.2-blue.svg)](https://profiles.ihe.net/ITI/MHD/)
[![Java](https://img.shields.io/badge/Java-17-green.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![HAPI FHIR](https://img.shields.io/badge/HAPI%20FHIR-7.6.0-blue.svg)](https://hapifhir.io/)

Built with **Spring Boot 3** and **HAPI FHIR 7.6.0**, this backend simulator implements the normative rules specified in `fhir-ig-specification/input/fsh/` and `fhir-ig-specification/input/pagecontent/`. It serves realistic, pre-configured FHIR JSON resources and binaries stored directly in a simple filesystem directory (`data/`).

---

## 1. Supported Interhub Transactions & Operations

| Transaction / Interaction | HTTP Method & Wire Endpoint | Implementation Details |
| :--- | :--- | :--- |
| **`getTransactionList`**<br/>*(MHD ITI-67 Find DocumentReferences)* | `POST [base]/DocumentReference/_search`<br/>(`application/x-www-form-urlencoded` body)<br/>*Also supports GET for general IHE conformance* | • Mandatory `patient.identifier` validation (Belgian SSIN / INSS).<br/>• Filters: `category`, `type` (LOINC), `date`, `author.identifier`, `status`, `_id`, `identifier`, `searchtype`, `_count`, `_sort`.<br/>• Returns FHIR `Bundle` (`type = searchset`) with `search.mode = match`.<br/>• Full partial failure support: downstream repository timeout/maintenance yields HTTP 200 OK with `OperationOutcome` at `search.mode = outcome`. |
| **`getTransaction`**<br/>*(MHD ITI-68 / `$retrieve-document`)* | `POST [base]/DocumentReference/$retrieve-document`<br/>(`application/fhir+json` Parameters body) | • Accepts `Parameters.parameter[name="documentReference"]`.<br/>• Resolves references to self-contained FHIR Document Bundles (`type = document`).<br/>• Supports Content Negotiation: requesting `Accept: application/pdf` returns the raw PDF binary stream directly with HTTP 200 OK.<br/>• Returns HTTP 410 Gone with `OperationOutcome` if document is withdrawn.<br/>• Returns HTTP 404 Not Found with `OperationOutcome` if document does not exist. |
| **Direct Document Read** | `GET [base]/Bundle/{id}` | Direct retrieval of complete clinical document bundles (conforming to `be-interhub-document-bundle`). |
| **Direct Metadata Read** | `GET [base]/DocumentReference/{id}` | Direct retrieval of metadata envelope (conforming to `be-interhub-documentreference`). |
| **Direct Binary Read** | `GET [base]/Binary/{id}` | Direct retrieval of binary artifacts (e.g. hub-rendered PDF report). |
| **Capabilities Discovery** | `GET [base]/metadata` | Returns the normative `BeInterhubDocumentResponder` CapabilityStatement from the IG. |

> **Path Forwarding**: Both `http://localhost:8080/fhir/...` (canonical base) and `http://localhost:8080/...` (without `/fhir` prefix) are supported transparently.

---

## 2. Directory Structure of Sample Data (`data/`)

All mock data is served from the `./data/` directory (configurable via `hub.simulator.data-dir`):

```
data/
├── document-references/
│   ├── DocumentReference-DocRefLabReportContainedExample.json   # MHD Comprehensive Contained Lab Report
│   ├── DocumentReference-DocRefLabReportExample.json            # Lab Report metadata alias
│   ├── DocumentReference-DocRefMinimalExample.json              # IHE MHD Minimal profile example
│   └── DocumentReference-DocRefTelemonitoringExample.json       # 24h Holter Ambulatory Telemonitoring
├── document-bundles/
│   ├── Bundle-BundleLabReportExample.json                       # Complete Lab Report Document Bundle (type=document)
│   └── Bundle-BundleTelemonitoringExample.json                  # Complete Telemonitoring Document Bundle (type=document)
├── searchsets/
│   └── Bundle-BundleTransactionListResponseExample.json         # Complete sample searchset with OperationOutcome
├── operation-outcomes/
│   └── OperationOutcome-OutcomePartialFailureExample.json       # Partial downstream repository timeout/maintenance
├── metadata/
│   ├── CapabilityStatement-BeInterhubDocumentResponder.json     # Normative responder CapabilityStatement
│   └── OperationDefinition-BeRetrieveDocument.json              # $retrieve-document OperationDefinition
├── binaries/
│   ├── rendered-lab-report-example-01.pdf                       # Hub-rendered sample lab report PDF
│   └── holter-001.pdf                                           # Holter telemonitoring report PDF
└── resources/
    ├── Patient-PatientPeeters.json                              # Jan Peeters (SSIN: 79080412345)
    ├── Practitioner-DrDanieleGovaerts.json                      # Clinical Biologist (NIHDI: 10000007999)
    ├── Practitioner-DrJeanDepondt.json                          # Cardiologist (NIHDI: 19876543201)
    ├── Organization-HubCoZo.json                                # CoZo Regional Hub (OID: 1.3.6.1.4.1.21297.1.3)
    ├── Organization-OrgUZLeuven.json                            # UZ Leuven Hospital (NIHDI: 71000012)
    └── (DiagnosticReports, Observations, Specimens, Devices)
```

To add additional clinical documents or patients to the simulator, simply drop your FHIR JSON or PDF files into the `data/` folder and restart or reload the service.

---

## 3. Prerequisites & Quickstart

### Prerequisites
- **Java 17+**
- **Apache Maven 3.8+**

### Build and Run

```bash
cd fhir-ehealth-hub-simulator

# 1. Run all 16 unit and integration tests
mvn clean test

# 2. Start the simulator server (runs on port 8080)
mvn spring-boot:run
```

Or run the packaged jar:
```bash
mvn package -DskipTests
java -jar target/fhir-ehealth-hub-simulator-1.0.0-SNAPSHOT.jar
```

### Pairing with the Web Viewer (`fhir-ehealthhub-simulator-viewer`)

You can explore and test this backend using the developer console in `../fhir-ehealthhub-simulator-viewer`:
1. Start this simulator on port 8080 (`mvn spring-boot:run`).
2. Start the viewer in another terminal (`cd ../fhir-ehealthhub-simulator-viewer && npm start`).
3. In the viewer at `http://localhost:4173`, navigate to **Connections** (`#settings`):
   - Set **Environment** to `Live · configured FHIR server`.
   - Set **FHIR base URL** to `http://localhost:8080/fhir`.
   - Set **HTTP transport** to `Local proxy · avoids browser CORS`.
4. Click **Save connection** and search for patient SSIN `79080412345`.

---

## 4. Live Examples & Curl Commands

### 4.1 Document Discovery (`getTransactionList` / MHD ITI-67)

#### Standard Query by Patient SSIN (POST form-urlencoded):
```bash
curl -X POST http://localhost:8080/fhir/DocumentReference/_search   -H "Content-Type: application/x-www-form-urlencoded"   -H "Accept: application/fhir+json; fhirVersion=4.0"   -d "patient.identifier=https%3A%2F%2Fwww.ehealth.fgov.be%2Fstandards%2Ffhir%2Fcore%2FNamingSystem%2Fssin%7C79080412345"
```

#### Filter by Belgian Document Category (`category=labresult`):
```bash
curl -X POST http://localhost:8080/fhir/DocumentReference/_search   -H "Content-Type: application/x-www-form-urlencoded"   -d "patient.identifier=79080412345&category=labresult"
```

#### Filter by Clinical LOINC Type (`type=http://loinc.org|18754-2` for Holter ECG):
```bash
curl -X POST http://localhost:8080/fhir/DocumentReference/_search   -H "Content-Type: application/x-www-form-urlencoded"   -d "patient.identifier=79080412345&type=http%3A%2F%2Floinc.org%7C18754-2"
```

#### Simulate Downstream Partial Failures (returns `search.mode = outcome` with `OperationOutcome`):
```bash
curl -X POST http://localhost:8080/fhir/DocumentReference/_search   -H "Content-Type: application/x-www-form-urlencoded"   -H "X-Simulate-Partial-Failure: true"   -d "patient.identifier=79080412345"
```

#### Missing Mandatory `patient.identifier` (Returns HTTP 400 Bad Request):
```bash
curl -i -X POST http://localhost:8080/fhir/DocumentReference/_search   -H "Content-Type: application/x-www-form-urlencoded"   -d "category=labresult"
```

#### Invalid / Malformed SSIN (Returns HTTP 400 Bad Request):
```bash
curl -i -X POST http://localhost:8080/fhir/DocumentReference/_search   -H "Content-Type: application/x-www-form-urlencoded"   -d "patient.identifier=12345"
```

---

## 4.2 Document Retrieval (`getTransaction` / MHD ITI-68 / `$retrieve-document`)

#### Retrieve Structured FHIR Document Bundle (Laboratory Report):
```bash
curl -X POST http://localhost:8080/fhir/DocumentReference/\$retrieve-document   -H "Content-Type: application/fhir+json"   -H "Accept: application/fhir+json; fhirVersion=4.0"   -d '{
    "resourceType": "Parameters",
    "parameter": [
      {
        "name": "documentReference",
        "valueReference": {
          "reference": "DocumentReference/DocRefLabReportContainedExample"
        }
      }
    ]
  }'
```

#### Retrieve Structured FHIR Document Bundle (Telemonitoring Report):
```bash
curl -X POST http://localhost:8080/fhir/DocumentReference/\$retrieve-document   -H "Content-Type: application/fhir+json"   -d '{
    "resourceType": "Parameters",
    "parameter": [
      {
        "name": "documentReference",
        "valueReference": {
          "reference": "DocumentReference/DocRefTelemonitoringExample"
        }
      }
    ]
  }'
```

#### Content Negotiation: Retrieve Hub-Rendered PDF Binary Stream:
```bash
curl -X POST http://localhost:8080/fhir/DocumentReference/\$retrieve-document   -H "Content-Type: application/fhir+json"   -H "Accept: application/pdf"   -d '{
    "resourceType": "Parameters",
    "parameter": [
      {
        "name": "documentReference",
        "valueReference": {
          "reference": "DocumentReference/DocRefLabReportContainedExample"
        }
      }
    ]
  }' --output lab-report.pdf
```

#### Withdrawn Document (Returns HTTP 410 Gone with `OperationOutcome`):
```bash
curl -i -X POST http://localhost:8080/fhir/DocumentReference/\$retrieve-document   -H "Content-Type: application/fhir+json"   -d '{
    "resourceType": "Parameters",
    "parameter": [
      {
        "name": "documentReference",
        "valueReference": {
          "reference": "DocumentReference/withdrawn"
        }
      }
    ]
  }'
```

#### Non-existent Document (Returns HTTP 404 Not Found with `OperationOutcome`):
```bash
curl -i -X POST http://localhost:8080/fhir/DocumentReference/\$retrieve-document   -H "Content-Type: application/fhir+json"   -d '{
    "resourceType": "Parameters",
    "parameter": [
      {
        "name": "documentReference",
        "valueReference": {
          "reference": "DocumentReference/non-existent-document"
        }
      }
    ]
  }'
```

---

## 4.3 Direct Reads & CapabilityStatement

#### CapabilityStatement Discovery:
```bash
curl http://localhost:8080/fhir/metadata
```

#### Direct Read of Complete Document Bundle:
```bash
curl http://localhost:8080/fhir/Bundle/BundleLabReportExample
```

#### Direct Read of Metadata Envelope:
```bash
curl http://localhost:8080/fhir/DocumentReference/DocRefLabReportContainedExample
```

#### Direct Read of Rendered PDF Binary:
```bash
curl -H "Accept: application/pdf" http://localhost:8080/fhir/Binary/rendered-lab-report-example-01 -o sample.pdf
```

---

## 5. Configuration Reference (`application.yml`)

```yaml
server:
  port: 8080

hub:
  simulator:
    # Filesystem directory containing sample resources and binaries
    data-dir: "./data"
    # Strict modulo-97 checksum enforcement on SSINs (false allows synthetic test data)
    strict-ssin-checksum: false
    # Return simulated partial failures by default on getTransactionList
    simulate-partial-failure: false
    # Responding Hub metadata
    hub-oid: "urn:oid:1.3.6.1.4.1.21297.1.3"
    hub-ehp: "1990000003"
    hub-name: "CoZo Regional Hub"
    server-base-url: "http://localhost:8080/fhir"
```
