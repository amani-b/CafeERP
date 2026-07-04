# CafeERP

CafeERP is a simple Spring Boot web application for managing a cafe's categories, menu items, and customer orders.

## Features

- Manage product categories
- Manage menu items
- Create and view orders
- Thymeleaf-based web interface

## Tech Stack

- Java 17+
- Spring Boot
- Spring MVC
- Spring Data JPA
- PostgreSQL
- Thymeleaf
- Maven

## Prerequisites

- Java 17 or later
- Maven
- PostgreSQL running locally

## Configuration

The application expects a PostgreSQL database. You can configure it with environment variables or by editing the datasource settings in [src/main/resources/application.properties](src/main/resources/application.properties).

Example:

```bash
export DB_URL=jdbc:postgresql://localhost:5432/cafe_erp
export DB_USERNAME=postgres
export DB_PASSWORD=your_password
```

## Running the Application

1. Clone the repository
2. Create or configure your PostgreSQL database
3. Set the database environment variables if needed
4. Run:

```bash
mvn spring-boot:run
```

Then open your browser at:

```text
http://localhost:8080/
```

## Project Structure

- [src/main/java](src/main/java) - application source code
- [src/main/resources/templates](src/main/resources/templates) - Thymeleaf templates
- [src/main/resources/application.properties](src/main/resources/application.properties) - application configuration

## Notes

This project is intended as a basic ERP-style demo for cafe operations and can be extended with inventory, payments, and reporting features.
