package io.aevumpay.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/pay/{id}")
    public String pay() {
        return "forward:/pay.html";
    }

    @GetMapping("/receipt/{id}")
    public String receipt() {
        return "forward:/receipt.html";
    }
}
