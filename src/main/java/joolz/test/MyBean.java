package joolz.test;

import javax.faces.bean.ManagedBean;
import javax.faces.bean.ViewScoped;

@ManagedBean
@ViewScoped
public class MyBean {

	public String getText() {
		return "Some text from the bean";
	}

}
