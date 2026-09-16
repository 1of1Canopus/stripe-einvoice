package com.housedevinci.einvoice.domain.en16931;

import com.housedevinci.einvoice.domain.ScreenedText;

/**
 * BG-6 and BG-9, a party's contact point. Mandatory on the seller for XRechnung (BR-DE-2, BR-DE-6,
 * BR-DE-7), optional everywhere else.
 *
 * @param name BT-41 / BT-56
 * @param telephone BT-42 / BT-57
 * @param email BT-43 / BT-58
 */
public record Contact(String name, String telephone, String email) {

  public Contact {
    name = ScreenedText.screen("contact name", name, BusinessTerms.CONTACT_NAME);
    telephone = ScreenedText.screen("contact telephone", telephone, BusinessTerms.TELEPHONE);
    email = ScreenedText.screen("contact email", email, BusinessTerms.EMAIL);
  }
}
