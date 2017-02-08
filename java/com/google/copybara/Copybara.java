/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara;

import com.google.common.base.Preconditions;
import com.google.copybara.Info.MigrationReference;
import com.google.copybara.config.ConfigLoader;
import com.google.copybara.config.ConfigValidator;
import com.google.copybara.util.console.Console;
import com.google.copybara.util.console.Message;
import com.google.copybara.util.console.Message.MessageType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Copybara tool main class.
 *
 * <p>Executes Copybara workflows independently from the environment that they are invoked from
 * (command-line, service).
 */
public class Copybara {

  private final ConfigValidator configValidator;

  public Copybara() {
    this.configValidator = new ConfigValidator();
  }

  public Copybara(ConfigValidator configValidator) {
    this.configValidator = Preconditions.checkNotNull(configValidator);
  }

  public void run(Options options, ConfigLoader<?> configLoader, String migrationName,
      Path workdir, @Nullable String sourceRef)
      throws RepoException, ValidationException, IOException {
    Config config = loadConfig(options, configLoader, migrationName);
    config.getMigration(migrationName).run(workdir, sourceRef);
  }

  public Config info(Options options, ConfigLoader<?> configLoader, String migrationName)
      throws IOException, ValidationException, RepoException {
    Console console = options.get(GeneralOptions.class).console();
    Config config = loadConfig(options, configLoader, migrationName);
    Info info = config.getMigration(migrationName).getInfo();
    for (MigrationReference ref : info.migrationReferences()) {
      console.info(
          String.format("'%s': last_migrated %s - last_available %s.",
              ref.getLabel(),
              ref.getLastMigrated() != null ? ref.getLastMigrated().asString() : "None",
              ref.getNextToMigrate() != null ? ref.getNextToMigrate().asString() : "None"));
    }
    return config;
  }

  public boolean validate(Options options, ConfigLoader<?> configLoader, String migrationName)
      throws RepoException, IOException {
    Console console = options.get(GeneralOptions.class).console();
    ArrayList<Message> messages = new ArrayList<>();
    try {
      Config config = configLoader.loadConfig(options);
      messages.addAll(validateConfig(config, migrationName));
    } catch (ValidationException e) {
      // The validate subcommand should not throw Validation exceptions but log a result
      StringBuilder error = new StringBuilder(e.getMessage()).append("\n");
      Throwable cause = e.getCause();
      while (cause != null) {
        error.append("  CAUSED BY: ").append(cause.getMessage()).append("\n");
        cause = cause.getCause();
      }
      messages.add(Message.error(error.toString()));
    }

    messages.forEach(message -> message.printTo(console));
    boolean hasNoErrors =
        messages.stream().noneMatch(message -> message.getType() == MessageType.ERROR);
    if (hasNoErrors) {
      console.info(String.format("Configuration '%s' is valid.", configLoader.location()));
    } else {
      console.error(String.format("Configuration '%s' is invalid.", configLoader.location()));
    }
    return hasNoErrors;
  }

  protected Config loadConfig(Options options, ConfigLoader<?> configLoader, String migrationName)
      throws IOException, ValidationException {
    GeneralOptions generalOptions = options.get(GeneralOptions.class);
    Console console = generalOptions.console();
    Config config = configLoader.loadConfig(options);
    console.progress("Validating configuration");
    List<Message> validationMessages = validateConfig(config, migrationName);

    List<Message> errors = validationMessages.stream()
        .filter(message -> message.getType() == MessageType.ERROR)
        .collect(Collectors.toList());
    if (errors.isEmpty()) {
      return config;
    }
    errors.forEach(error -> error.printTo(console));
    console.error("Configuration is invalid.");
    throw new ValidationException("Error validating configuration: Configuration is invalid.");
  }

  /**
   * Returns a list of validation error messages, if any, for the given configuration.
   */
  private List<Message> validateConfig(Config config, String migrationName) {
    return configValidator.validate(config, migrationName);
  }
}
