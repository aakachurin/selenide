package com.codeborne.selenide.impl;

import com.codeborne.selenide.Condition;
import com.codeborne.selenide.Driver;
import com.codeborne.selenide.SelenideElement;
import com.codeborne.selenide.ex.ElementNotFound;
import org.openqa.selenium.WebElement;

import java.lang.reflect.Proxy;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class LazyWebElementSnapshot extends WebElementSource {

  public static SelenideElement wrap(WebElementSource delegate) {
    return (SelenideElement) Proxy.newProxyInstance(
      Thread.currentThread().getContextClassLoader(),
      new Class<?>[] {SelenideElement.class},
      new SelenideElementProxy(new LazyWebElementSnapshot(delegate))
    );
  }

  private final WebElementSource delegate;

  private WebElement snapshot;

  LazyWebElementSnapshot(WebElementSource delegate) {
    this.delegate = delegate;
  }

  @Nonnull
  @Override
  public Driver driver() {
    return delegate.driver();
  }

  @Nonnull
  @Override
  public WebElement getWebElement() {
    if (snapshot == null) {
      snapshot = delegate.getWebElement();
    }
    return snapshot;
  }

  @Nonnull
  @Override
  public String getSearchCriteria() {
    return delegate.getSearchCriteria();
  }

  @Override
  public void setAlias(String alias) {
    delegate.setAlias(alias);
  }

  @Nonnull
  @Override
  public Alias getAlias() {
    return delegate.getAlias();
  }

  @Nonnull
  @Override
  public String description() {
    return delegate.description();
  }

  @Nonnull
  @Override
  public String toString() {
    return delegate.toString();
  }

  @Nonnull
  @Override
  public SelenideElement find(SelenideElement proxy, Object arg, int index) {
    return delegate.find(proxy, arg, index);
  }

  @Nonnull
  @Override
  public List<WebElement> findAll() throws IndexOutOfBoundsException {
    return delegate.findAll();
  }

  @Nonnull
  @Override
  public ElementNotFound createElementNotFoundError(Condition condition, Throwable lastError) {
    return delegate.createElementNotFoundError(condition, lastError);
  }
}
