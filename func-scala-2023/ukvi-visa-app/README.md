# UKVI Visa application app

How to run the example:

1. Run the [Main object](src/main/scala/dev/vhonta/ukvi/visa/Main.scala)
2. Open http://localhost:9090/ in your browser. You should see the following page:
   ![start](../media/ukvi-visa-start.png)
3. Enter anything in the email box and proceed with the application!
4. At the end, you're form should look like this
   ![user-data-filled](../media/ukvi-visa-user-data-filled.png)
5. Update the URL in your browser to access admin UI. For instance, if the application has url
   like `http://localhost:9090/api/v1/visitor/application/:uuid:/`, make
   it `http://localhost:9090/api/v1/admin/visitor/application/:uuid:/`
6. The admin UI looks like follows:
   ![admin-ui](../media/ukvi-visa-admin-ui.png)
7. Click on the "approve" checkbox and "make decision" button. The application is now approved
   ![admin](../media/ukvi-visa-admin-approved.png)
8. Go back to the regular UI `http://localhost:9090/api/v1/visitor/application/:uuid:/`. Your application should like
   the following:
   ![approved](../media/ukvi-visa-approved.png)