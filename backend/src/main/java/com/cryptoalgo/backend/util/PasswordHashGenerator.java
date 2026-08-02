package com.cryptoalgo.backend.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.Console;
import java.util.Scanner;

/**
 * Standalone utility to generate and verify BCrypt password hashes.
 *
 * Run:
 *   java ... PasswordHashGenerator
 *
 * Or from your IDE by running the main() method.
 */
public class PasswordHashGenerator {

    private static final BCryptPasswordEncoder ENCODER =
            new BCryptPasswordEncoder();

    public static void main(String[] args) {

        System.out.println("========================================");
        System.out.println("      BCrypt Password Hash Generator");
        System.out.println("========================================");

        String password;

        if (args.length >= 1) {
            password = args[0];
        } else {
            Console console = System.console();

            if (console != null) {
                char[] passwordChars =
                        console.readPassword("Enter password: ");

                if (passwordChars == null || passwordChars.length == 0) {
                    System.out.println("Password cannot be empty.");
                    return;
                }

                password = new String(passwordChars);

            } else {
                // Useful when running from IntelliJ/Eclipse console
                Scanner scanner = new Scanner(System.in);

                System.out.print("Enter password: ");
                password = scanner.nextLine();

                if (password.isBlank()) {
                    System.out.println("Password cannot be empty.");
                    return;
                }
            }
        }

        String hashedPassword = generateHash(password);

        System.out.println();
        System.out.println("Generated BCrypt hash:");
        System.out.println(hashedPassword);
        System.out.println();
        System.out.println("Copy the hash above into your database.");
    }

    /**
     * Generates a BCrypt hash for the given plain text password.
     */
    public static String generateHash(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password cannot be null or empty");
        }

        return ENCODER.encode(password);
    }

    /**
     * Verifies whether a plain text password matches a stored BCrypt hash.
     */
    public static boolean verifyPassword(String password, String hash) {
        if (password == null || hash == null) {
            return false;
        }

        return ENCODER.matches(password, hash);
    }
}