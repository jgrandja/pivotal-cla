/*
 * Copyright 2002-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.pivotal.cla.webdriver.pages.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.PageFactory;

import io.pivotal.cla.webdriver.pages.BasePage;

public class AdminHelpPage extends BasePage {
	WebElement migrate;

	public AdminHelpPage(WebDriver driver) {
		super(driver);
	}

	public AdminHelpMigratePage migrate() {
		migrate.click();
		return PageFactory.initElements(getDriver(), AdminHelpMigratePage.class);
	}

	public void assertAt() {
		assertThat(getDriver().getTitle()).endsWith("Admin Help");
	}

	public static String url() {
		return "/admin/help";
	}

	public static AdminHelpPage to(WebDriver driver) {
		get(driver, url());
		return PageFactory.initElements(driver, AdminHelpPage.class);
	}
}
